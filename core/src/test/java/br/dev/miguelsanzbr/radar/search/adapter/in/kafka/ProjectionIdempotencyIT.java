package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.search.SearchFixtures;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan proof of the CQRS read side: the same batch of 200 envelopes goes through the topic three
 * times and the search schema does not change after the first pass. Its own topic and group keep it
 * apart from the events other test classes leave on the shared broker.
 */
@KafkaSpringBootTest
@TestPropertySource(
    properties = {
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-idempotency-it",
      "radar.outbox.topics.Procurement=radar.procurement.idempotency-it.v1",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class ProjectionIdempotencyIT {

  private static final String TOPIC = "radar.procurement.idempotency-it.v1";
  private static final String GROUP = "search-projector-idempotency-it";
  private static final int AGGREGATES = 50;

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  /**
   * A fresh 12 partition topic can answer NOT_LEADER_OR_FOLLOWER for a while on a loaded broker, so
   * the test sends an envelope of an unknown type to every partition until all accept it. The
   * projector acknowledges and ignores them.
   */
  @BeforeEach
  void topicIsReady() {
    var probe =
        envelope(
            "Probe", closed("probe", id(0), 1), JsonMapper.builder().build().createObjectNode());
    await()
        .atMost(Duration.ofSeconds(150))
        .ignoreExceptions()
        .until(
            () -> {
              for (var partition = 0; partition < 12; partition++) {
                template.send(TOPIC, partition, "probe", probe).get(10, TimeUnit.SECONDS);
              }
              return true;
            });
  }

  /** Discovered, Updated, SituationChanged and Closed for each of 50 aggregates: 200 events. */
  static List<ProcurementEvent> batch() {
    var events = new ArrayList<ProcurementEvent>();
    for (var i = 0; i < AGGREGATES; i++) {
      var id = id(i);
      var to = i % 2 == 0 ? 4 : 3;
      var value = i % 5 == 0 ? null : Integer.toString(10_000 * (i + 1)) + ".00";
      var first = view(id, "Contratação de serviços " + i, value, 1);
      var second =
          view(id, "Aquisição de material " + i, value, to, 2, SearchFixtures.T0.plusSeconds(60));
      events.add(discovered("d" + i, first, 1));
      events.add(updated("u" + i, second, 2));
      events.add(situationChanged("s" + i, id, 2, 1, to));
      events.add(closed("c" + i, id, 3));
    }
    return events;
  }

  private void publish(List<ProcurementEvent> events) throws Exception {
    var sends = new ArrayList<CompletableFuture<?>>();
    for (var event : events) {
      sends.add(template.send(TOPIC, event.aggregateId(), envelope(event)));
    }
    for (var send : sends) {
      send.get(120, TimeUnit.SECONDS);
    }
  }

  /** The group has committed everything that was published: its lag is zero. */
  private void awaitConsumed(long published) {
    var bootstrap = SharedKafka.INSTANCE.getBootstrapServers();
    try (var admin =
            Admin.create(
                Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        var reader =
            new KafkaConsumer<String, String>(
                Map.<String, Object>of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    bootstrap,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class))) {
      await()
          .atMost(Duration.ofSeconds(150))
          .pollInterval(Duration.ofMillis(300))
          .until(
              () -> {
                var committed =
                    admin
                        .listConsumerGroupOffsets(GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get()
                        .values()
                        .stream()
                        .mapToLong(OffsetAndMetadata::offset)
                        .sum();
                var partitions =
                    reader.partitionsFor(TOPIC).stream()
                        .map(p -> new TopicPartition(TOPIC, p.partition()))
                        .toList();
                var end = reader.endOffsets(partitions).values().stream().mapToLong(o -> o).sum();
                return end >= published && committed == end;
              });
    }
  }

  /** The checkpoint is left out: its offset depends on how the 12 partitions interleaved. */
  private String dump() {
    var queries =
        List.of(
            "SELECT numero_controle_pncp, search_vector::text, object_description, organ_name,"
                + " unit_name, uf, ibge_code, municipality_name, modality_id, status,"
                + " estimated_value, value_band, proposal_opening_at, proposal_closing_at,"
                + " published_at, pncp_updated_at, source_version, source"
                + " FROM search.procurement_search_view ORDER BY numero_controle_pncp",
            "SELECT numero_controle_pncp, detail::text, etag, source_version"
                + " FROM search.procurement_detail_view ORDER BY numero_controle_pncp",
            "SELECT consumer, event_id FROM search.processed_event ORDER BY consumer, event_id",
            "SELECT uf, modality_id, month, value_band, count, estimated_sum"
                + " FROM search.procurement_stats_view ORDER BY uf, modality_id, month, value_band");
    return queries.stream()
        .map(
            sql ->
                jdbc.queryForList(sql).stream()
                    .map(
                        row ->
                            row.values().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining("|")))
                    .collect(Collectors.joining("\n")))
        .collect(Collectors.joining("\n--\n"));
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void theSameBatchThreeTimesLeavesTheReadSideByteIdentical() throws Exception {
    var events = batch();
    assertThat(events).hasSize(200);
    var hashes = new ArrayList<String>();

    for (var pass = 1; pass <= 3; pass++) {
      publish(events);
      awaitConsumed(200L * pass);
      hashes.add(sha256(dump()));
    }

    assertThat(hashes).hasSize(3).containsOnly(hashes.getFirst());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM search.processed_event", Integer.class))
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM search.projection_checkpoint", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM search.procurement_search_view", Integer.class))
        .isEqualTo(AGGREGATES);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM search.procurement_detail_view WHERE"
                    + " detail->'closedAt' IS NOT NULL AND jsonb_array_length(detail->'history') = 2",
                Integer.class))
        .isEqualTo(AGGREGATES);
    var lines = new ArrayList<String>();
    for (var pass = 0; pass < hashes.size(); pass++) {
      lines.add("pass " + (pass + 1) + " " + hashes.get(pass));
    }
    Files.writeString(
        Path.of("target", "idempotency-hashes.txt"),
        String.join("\n", lines) + "\n",
        StandardCharsets.UTF_8);
  }
}
