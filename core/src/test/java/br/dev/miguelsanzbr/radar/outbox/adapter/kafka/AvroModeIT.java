package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.SharedApicurio;
import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The avro mode end to end: ingest appends to the outbox, the relay publishes Avro (Confluent
 * framing) to Kafka, the search projector projects it, the alerts matcher matches it and the relay
 * publishes the AlertMatched in Avro too. The groups and their BACKWARD rule come from
 * scripts/schemas-register.sh, the same step an operator runs. Own topics keep it apart from the
 * events other test classes leave on the shared broker.
 */
@Tag("avro")
@KafkaSpringBootTest
@TestPropertySource(
    properties = {
      "radar.messaging.format=avro",
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-avro-it",
      "radar.alerts.matcher.enabled=true",
      "radar.outbox.topics.Procurement=" + AvroModeIT.PROCUREMENT,
      "radar.outbox.topics.Alert=" + AvroModeIT.ALERT,
      // why: a fresh topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class AvroModeIT {

  static final String PROCUREMENT = "radar.procurement.avro-it.v1";
  static final String ALERT = "radar.alert.avro-it.v1";

  private static final Instant NOW = Instant.now();

  @DynamicPropertySource
  static void registry(DynamicPropertyRegistry properties) {
    properties.add("radar.messaging.registry-url", SharedApicurio::url);
  }

  @Autowired OutboxAppender appender;
  @Autowired OutboxRelay relay;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaListenerEndpointRegistry listeners;

  private static void registerGroups() throws IOException, InterruptedException {
    var builder =
        new ProcessBuilder(
            "bash",
            Path.of("..", "scripts", "schemas-register.sh").toString(),
            SharedApicurio.url());
    builder.environment().put("RADAR_TOPICS", PROCUREMENT + " " + ALERT);
    var process = builder.redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
  }

  private static void until(BooleanSupplier condition) {
    var deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met in 90 s");
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private long count(String from) {
    return jdbc.sql("SELECT count(*) FROM " + from).query(Long.class).single();
  }

  /** The raw bytes of the topic: the avro records, not text. */
  private static List<ConsumerRecord<String, byte[]>> raw(String topic, int atLeast) {
    var records = new ArrayList<ConsumerRecord<String, byte[]>>();
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedKafka.INSTANCE.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class))) {
      consumer.subscribe(List.of(topic));
      var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (records.size() < atLeast && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
    }
    return records;
  }

  /** The id in the Confluent framing: a zero byte, then 4 bytes big-endian. */
  private static int schemaId(byte[] value) {
    var framing = ByteBuffer.wrap(value);
    assertThat(framing.get()).isZero();
    return framing.getInt();
  }

  private String groupRule(String topic) {
    var rule =
        new RegistryHttp(SharedApicurio.url())
            .call("GET", "/groups/" + topic + "/rules/COMPATIBILITY", null);
    assertThat(rule.status()).isEqualTo(200);
    return rule.body();
  }

  @Test
  void aProcurementFlowsAsAvroFromTheOutboxToTheProjectionAndTheAlert() throws Exception {
    registerGroups();
    var matcher = listeners.getListenerContainer("alerts-matcher");
    until(() -> matcher.getAssignedPartitions().size() == 12);
    jdbc.sql(
            "TRUNCATE alerts.alert_delivery, alerts.saved_search, alerts.verification_token,"
                + " alerts.idempotency_key, alerts.subscriber, alerts.processed_event,"
                + " search.procurement_search_view, search.procurement_detail_view,"
                + " search.processed_event, search.projection_checkpoint")
        .update();
    jdbc.sql("DELETE FROM outbox.outbox_event").update();
    var subscriber = MatcherData.subscriber(jdbc);
    MatcherData.search(jdbc, subscriber, "escritorio", "{\"uf\":\"SC\"}");

    var discovery = Procurement.discover(snapshot(id(910_501)), clockAt(NOW));
    transaction.executeWithoutResult(
        status -> appender.append("Procurement", 1, discovery.events()));
    until(
        () -> {
          relay.relayOnce();
          return count(
                  "outbox.outbox_event WHERE aggregate_type = 'Procurement' AND published_at IS NULL")
              == 0;
        });

    // the projector and the matcher read it: the read model has it and one alert row was written
    until(() -> count("search.procurement_search_view") == 1);
    until(() -> count("alerts.alert_delivery") == 1);
    until(
        () -> {
          relay.relayOnce();
          return count(
                  "outbox.outbox_event WHERE aggregate_type = 'Alert' AND published_at IS NULL")
              == 0;
        });

    var procurement = raw(PROCUREMENT, 1).getFirst();
    var procurementSchema = schemaId(procurement.value());
    assertThat(new String(procurement.headers().lastHeader("content-type").value()))
        .isEqualTo("application/avro");
    var alert = raw(ALERT, 1).getFirst();
    schemaId(alert.value());

    // a Confluent client resolves the id of the record through the compatible API of the registry
    var confluent =
        new RegistryHttp(SharedApicurio.root())
            .call("GET", "/apis/ccompat/v7/schemas/ids/" + procurementSchema, null);
    assertThat(confluent.status()).isEqualTo(200);
    assertThat(confluent.body()).contains("ProcurementDiscovered");

    // one group per topic, one artifact per event type, BACKWARD on the group
    assertThat(groupRule(PROCUREMENT)).contains("BACKWARD");
    assertThat(groupRule(ALERT)).contains("BACKWARD");
    var artifacts =
        new RegistryHttp(SharedApicurio.url())
            .call("GET", "/groups/" + PROCUREMENT + "/artifacts", null);
    assertThat(artifacts.body()).contains("ProcurementDiscovered");
    assertThat(
            new RegistryHttp(SharedApicurio.url())
                .call("GET", "/groups/" + ALERT + "/artifacts", null)
                .body())
        .contains("AlertMatched");
  }
}
