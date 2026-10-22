package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.outbox.EventPublisher;
import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import br.dev.miguelsanzbr.radar.outbox.OutboxRows;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.json.JsonMapper;

@KafkaSpringBootTest
class KafkaEventPublisherIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String STORED_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String STORED_SPAN_ID = "b7ad6b7169203331";
  private static final String W3C = "00-[0-9a-f]{32}-[0-9a-f]{16}-0[0-3]";

  @Autowired OutboxRelay relay;
  @Autowired EventPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired ProducerFactory<?, ?> producerFactory;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  @Test
  void relayPublishesThreeEventsOfTwoAggregatesAndTheyReadBackInOrder() throws Exception {
    var a1 =
        OutboxRows.insertWithHeaders(
            jdbc, "IT-A", "ProcurementDiscovered", "{\"aggregateVersion\":1}");
    var b1 =
        OutboxRows.insertWithHeaders(
            jdbc,
            "IT-B",
            "ProcurementDiscovered",
            "{\"aggregateVersion\":1,\"traceparent\":\"00-"
                + STORED_TRACE_ID
                + "-"
                + STORED_SPAN_ID
                + "-01\"}");
    var a2 =
        OutboxRows.insertWithHeaders(
            jdbc,
            "IT-A",
            "ProcurementUpdated",
            "{\"aggregateVersion\":2,\"occurredAt\":\"2026-10-21T08:00:00Z\"}");
    var ours = List.of(a1.toString(), b1.toString(), a2.toString());

    assertThat(publisher).isInstanceOf(KafkaEventPublisher.class);
    assertThat(relay.relayOnce()).isEqualTo(3);

    var read =
        TopicReader.readUntil(
            "radar.procurement.v1",
            all -> all.stream().filter(r -> ours.contains(eventId(r))).count() >= 3,
            Duration.ofSeconds(30));
    var mine = read.stream().filter(r -> ours.contains(eventId(r))).toList();
    assertThat(mine).hasSize(3);
    assertThat(mine).extracting(ConsumerRecord::key).containsOnly("IT-A", "IT-B");
    assertThat(
            mine.stream().filter(r -> r.key().equals("IT-A")).map(KafkaEventPublisherIT::eventId))
        .containsExactly(a1.toString(), a2.toString());

    var second =
        mine.stream().filter(r -> eventId(r).equals(a2.toString())).findFirst().orElseThrow();
    var envelope = JSON.readTree(second.value());
    assertThat(envelope.get("eventId").asString()).isEqualTo(a2.toString());
    assertThat(envelope.get("eventType").asString()).isEqualTo("ProcurementUpdated");
    assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(envelope.get("aggregateType").asString()).isEqualTo("Procurement");
    assertThat(envelope.get("aggregateId").asString()).isEqualTo("IT-A");
    assertThat(envelope.get("aggregateVersion").asInt()).isEqualTo(2);
    assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-21T08:00:00Z");
    assertThat(envelope.get("traceparent").isNull()).isTrue();
    assertThat(envelope.get("payload").get("k").asInt()).isEqualTo(1);
    assertThat(new String(second.headers().lastHeader("eventType").value()))
        .isEqualTo("ProcurementUpdated");
    assertThat(new String(second.headers().lastHeader("schemaVersion").value())).isEqualTo("1");
    // a row without a stored trace still leaves with a valid W3C header of a fresh trace
    var fresh = new String(second.headers().lastHeader("traceparent").value());
    assertThat(fresh).matches(W3C);
    assertThat(fresh).doesNotContain(STORED_TRACE_ID);

    var traced =
        mine.stream().filter(r -> eventId(r).equals(b1.toString())).findFirst().orElseThrow();
    // a stored trace is continued: same traceId, a new span id (the producer span), body untouched
    var continued = new String(traced.headers().lastHeader("traceparent").value());
    assertThat(continued).matches(W3C);
    assertThat(continued).contains("-" + STORED_TRACE_ID + "-").doesNotContain(STORED_SPAN_ID);
    assertThat(JSON.readTree(traced.value()).get("traceparent").asString())
        .isEqualTo("00-" + STORED_TRACE_ID + "-" + STORED_SPAN_ID + "-01");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NOT NULL",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void producerIsConfiguredForDurability() {
    var config = ((DefaultKafkaProducerFactory<?, ?>) producerFactory).getConfigurationProperties();

    assertThat(String.valueOf(config.get(ProducerConfig.ACKS_CONFIG))).isEqualTo("all");
    assertThat(String.valueOf(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)))
        .isEqualTo("true");
    assertThat(String.valueOf(config.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)))
        .isEqualTo("5");
    assertThat(String.valueOf(config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)))
        .isEqualTo("30000");
    assertThat(config.get(ProducerConfig.RETRIES_CONFIG)).isNull();
    assertThat(config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
        .isEqualTo(StringSerializer.class);
    assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
        .isEqualTo(StringSerializer.class);
  }

  @Test
  void kafkaAdminCreatedTheTopicsWithTheirPartitionCounts() throws Exception {
    try (var admin =
        Admin.create(Map.of("bootstrap.servers", SharedKafka.INSTANCE.getBootstrapServers()))) {
      var topics =
          admin
              .describeTopics(
                  List.of(
                      "radar.procurement.v1",
                      "radar.alert.v1",
                      "radar.procurement.v1.dlq",
                      "radar.alert.v1.dlq"))
              .allTopicNames()
              .get();

      assertThat(topics.get("radar.procurement.v1").partitions()).hasSize(12);
      assertThat(topics.get("radar.alert.v1").partitions()).hasSize(6);
      assertThat(topics.get("radar.procurement.v1.dlq").partitions()).hasSize(12);
      assertThat(topics.get("radar.alert.v1.dlq").partitions()).hasSize(6);
      assertThat(topics.get("radar.alert.v1").partitions().getFirst().replicas()).hasSize(1);
    }
  }

  @Test
  void onlyTheProcurementTopicKeepsEventsForeverForReplay() throws Exception {
    try (var admin =
        Admin.create(Map.of("bootstrap.servers", SharedKafka.INSTANCE.getBootstrapServers()))) {
      var procurement = new ConfigResource(ConfigResource.Type.TOPIC, "radar.procurement.v1");
      var alert = new ConfigResource(ConfigResource.Type.TOPIC, "radar.alert.v1");

      var configs = admin.describeConfigs(List.of(procurement, alert)).all().get();

      assertThat(configs.get(procurement).get("retention.ms").value()).isEqualTo("-1");
      assertThat(configs.get(alert).get("retention.ms").value()).isNotEqualTo("-1");
    }
  }

  private static String eventId(ConsumerRecord<String, String> record) {
    return new String(record.headers().lastHeader("eventId").value());
  }
}
