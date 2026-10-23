package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.LiveProjection;
import br.dev.miguelsanzbr.radar.search.application.ReplayUseCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plan proof of the rebuild: 50 000 events go through the topic and the live projector, the views
 * are dumped, the replay wipes and rebuilds them from offset 0 in a new consumer group, and the
 * dump is the same. Its own topic and group keep it apart from the other test classes.
 */
@KafkaSpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(
    properties = {
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-replay-it",
      "radar.outbox.topics.Procurement=radar.procurement.replay-it.v1",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class ProjectionReplayIT {

  private static final String TOPIC = "radar.procurement.replay-it.v1";
  private static final int AGGREGATES = 12_500;
  private static final int EVENTS = AGGREGATES * 4;
  private static final int PARTITIONS = 12;

  private static final String[] VERBS = {
    "Aquisição",
    "Contratação",
    "Registro de preços para",
    "Fornecimento de",
    "Locação de",
    "Prestação de serviços de",
    "Manutenção de",
    "Execução de obras de"
  };
  private static final String[] THINGS = {
    "material de consumo", "medicamentos e insumos hospitalares", "merenda escolar",
    "equipamentos de informática", "veículos leves", "pavimentação asfáltica",
    "serviços de limpeza e conservação", "combustíveis e lubrificantes", "mobiliário escolar",
    "software de gestão pública", "material de expediente", "uniformes e fardamentos",
    "reforma de unidades básicas de saúde", "serviços de engenharia consultiva"
  };
  private static final String[] PURPOSES = {
    "para atender as necessidades da Secretaria Municipal de Educação",
    "destinados às unidades de saúde do município durante o exercício de 2026",
    "conforme especificações e quantitativos constantes do termo de referência",
    "para manutenção das atividades administrativas e operacionais do órgão",
    "incluindo instalação, treinamento e garantia de doze meses"
  };

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReplayUseCase replay;
  @Autowired KafkaListenerEndpointRegistry registry;
  @MockitoSpyBean LiveProjection live;

  /**
   * A fresh 12 partition topic can answer NOT_LEADER_OR_FOLLOWER for a long while on a loaded
   * broker, and sends to such a partition expire. Each partition gets a record of an unknown type
   * (projectors ignore it) until it accepts one. The replay reads those too, so the test compares
   * its report with the size of the topic, not with the number of events it published.
   */
  @BeforeEach
  void everyPartitionAcceptsWrites() {
    var probe =
        envelope(
            "Probe", closed("probe", id(0), 1), JsonMapper.builder().build().createObjectNode());
    var accepted = new HashSet<Integer>();
    await()
        .atMost(Duration.ofSeconds(170))
        .ignoreExceptions()
        .until(
            () -> {
              for (var partition = 0; partition < PARTITIONS; partition++) {
                if (!accepted.contains(partition)) {
                  template.send(TOPIC, partition, "probe", probe).get(10, TimeUnit.SECONDS);
                  accepted.add(partition);
                }
              }
              return true;
            });
  }

  private long topicSize() throws Exception {
    try (var admin =
        Admin.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedKafka.INSTANCE.getBootstrapServers()))) {
      var latest = new HashMap<TopicPartition, OffsetSpec>();
      for (var partition = 0; partition < PARTITIONS; partition++) {
        latest.put(new TopicPartition(TOPIC, partition), OffsetSpec.latest());
      }
      return admin.listOffsets(latest).all().get().values().stream()
          .mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset)
          .sum();
    }
  }

  /**
   * Other test classes leave rows behind with the same aggregate ids and event ids (the fixtures
   * derive both from a seed), which the version guard and the inbox would honour.
   */
  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.procurement_stats_view, search.processed_event,"
            + " search.projection_checkpoint");
  }

  /** Discovered, Updated, SituationChanged and Closed for each aggregate: 50 000 events. */
  private void publishEverything() throws Exception {
    var random = new Random(20261022);
    var sends = new ArrayList<CompletableFuture<?>>(EVENTS);
    for (var i = 0; i < AGGREGATES; i++) {
      var id = id(i);
      var to = i % 2 == 0 ? 4 : 3;
      var value = i % 5 == 0 ? null : (random.nextInt(9_000_000) + 1_000) + ".00";
      var first = view(id, text(random), value, 1);
      var second = view(id, text(random), value, to, 2, T0.plusSeconds(60));
      for (var event :
          List.of(
              discovered("d" + i, first, 1),
              updated("u" + i, second, 2),
              situationChanged("s" + i, id, 2, 1, to),
              closed("c" + i, id, 3))) {
        sends.add(template.send(TOPIC, id, envelope(event)));
      }
      if (sends.size() >= 5_000) {
        drain(sends);
      }
    }
    drain(sends);
  }

  private static void drain(List<CompletableFuture<?>> sends) throws Exception {
    for (var send : sends) {
      send.get(180, TimeUnit.SECONDS);
    }
    sends.clear();
  }

  private static String text(Random random) {
    return VERBS[random.nextInt(VERBS.length)]
        + " "
        + THINGS[random.nextInt(THINGS.length)]
        + " "
        + PURPOSES[random.nextInt(PURPOSES.length)]
        + " (lote "
        + random.nextInt(500)
        + ")";
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM search." + table, Integer.class);
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** One digest per row, keyed by table and key; timestamps are left out because they differ. */
  private Map<String, String> dump() {
    var rows = new TreeMap<String, String>();
    var queries =
        Map.of(
            "search",
            "SELECT numero_controle_pncp, numero_controle_pncp || '|' || search_vector::text"
                + " || '|' || object_description || '|' || organ_name || '|' || coalesce(unit_name,'')"
                + " || '|' || coalesce(uf,'') || '|' || coalesce(ibge_code,'')"
                + " || '|' || coalesce(municipality_name,'') || '|' || modality_id || '|' || status"
                + " || '|' || coalesce(estimated_value::text,'') || '|' || value_band"
                + " || '|' || coalesce(proposal_opening_at::text,'')"
                + " || '|' || coalesce(proposal_closing_at::text,'') || '|' || published_at"
                + " || '|' || pncp_updated_at || '|' || source_version || '|' || source"
                + " FROM search.procurement_search_view",
            "detail",
            "SELECT numero_controle_pncp, detail::text || '|' || etag || '|' || source_version"
                + " FROM search.procurement_detail_view",
            "stats",
            "SELECT uf || modality_id || month || value_band, count || '|' || estimated_sum"
                + " FROM search.procurement_stats_view",
            "inbox",
            "SELECT consumer || event_id, consumer FROM search.processed_event");
    queries.forEach(
        (table, sql) ->
            jdbc.query(
                sql,
                (RowCallbackHandler)
                    rs -> rows.put(table + " " + rs.getString(1), sha256(rs.getString(2)))));
    return rows;
  }

  @Test
  @Order(1)
  void replayingFiftyThousandEventsRebuildsTheViewsExactly() throws Exception {
    var startedPublishing = System.nanoTime();
    publishEverything();
    var published = Duration.ofNanos(System.nanoTime() - startedPublishing);
    await()
        .atMost(Duration.ofMinutes(10))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> count("processed_event") == EVENTS);
    var liveProjected = Duration.ofNanos(System.nanoTime() - startedPublishing).minus(published);
    var before = dump();
    assertThat(count("procurement_search_view")).isEqualTo(AGGREGATES);
    assertThat(registry.getListenerContainer(ProcurementEventsListener.ID).isRunning()).isTrue();

    var report = replay.replay();

    var after = dump();
    assertThat(after).isEqualTo(before);
    assertThat(report.events()).isEqualTo(topicSize()).isGreaterThanOrEqualTo(EVENTS);
    assertThat(report.duration()).isPositive();
    assertThat(report.eventsPerSecond())
        .isEqualTo(report.events() / (report.duration().toNanos() / 1e9));
    assertThat(report.finishedAt()).isAfter(report.startedAt());
    assertThat(count("processed_event")).isEqualTo(EVENTS);
    assertThat(count("projection_checkpoint")).isEqualTo(1);
    var order = inOrder(live);
    order.verify(live).stop();
    order.verify(live).start();
    var line =
        ("published %d events in %.1f s, live projector caught up %.1f s later; replay %d events"
                + " in %.1f s (%.0f events/s)")
            .formatted(
                EVENTS,
                published.toMillis() / 1000.0,
                liveProjected.toMillis() / 1000.0,
                report.events(),
                report.duration().toMillis() / 1000.0,
                report.eventsPerSecond());
    System.out.println("REPLAY " + line);
    Files.writeString(Path.of("target", "replay-report.txt"), line + "\n");
  }

  @Test
  @Order(2)
  void theLiveListenerRunsAgainAfterTheReplayAndKeepsProjecting() {
    assertThat(registry.getListenerContainer(ProcurementEventsListener.ID).isRunning()).isTrue();

    var event =
        discovered("after-replay", view(id(99_999), "Aquisição depois do replay", null, 1), 1);
    template.send(TOPIC, id(99_999), envelope(event));

    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM search.procurement_search_view"
                            + " WHERE numero_controle_pncp = ?",
                        Integer.class,
                        id(99_999))
                    == 1);
  }
}
