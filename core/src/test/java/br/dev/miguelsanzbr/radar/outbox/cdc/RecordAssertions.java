package br.dev.miguelsanzbr.radar.outbox.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The one assertion both modes go through: what a consumer of the topic can rely on. */
public final class RecordAssertions {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What the test appended, as the consumer must see it. */
  public record Expected(
      String topic,
      String key,
      String eventType,
      String aggregateType,
      long aggregateVersion,
      String occurredAt,
      JsonNode payload) {}

  private RecordAssertions() {}

  /** The contract of a record, whichever mode published it. */
  public static void assertContract(
      ConsumerRecord<String, String> record, Expected expected, String storedTraceparent) {
    assertThat(record.topic()).isEqualTo(expected.topic());
    assertThat(record.key()).isEqualTo(expected.key());
    var envelope = JSON.readTree(record.value());
    assertThat(envelope.get("eventId").asString()).isEqualTo(header(record, "eventId"));
    assertThat(envelope.get("eventType").asString()).isEqualTo(expected.eventType());
    assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(envelope.get("aggregateType").asString()).isEqualTo(expected.aggregateType());
    assertThat(envelope.get("aggregateId").asString()).isEqualTo(expected.key());
    assertThat(envelope.get("aggregateVersion").asLong()).isEqualTo(expected.aggregateVersion());
    assertThat(envelope.get("occurredAt").asString()).isEqualTo(expected.occurredAt());
    assertThat(envelope.get("traceparent").asString()).isEqualTo(storedTraceparent);
    assertThat(envelope.get("payload")).isEqualTo(expected.payload());
    assertThat(header(record, "eventType")).isEqualTo(expected.eventType());
    assertThat(header(record, "schemaVersion")).isEqualTo("1");
    assertThat(traceId(record)).isEqualTo(traceId(storedTraceparent));
  }

  /** Two records of the same logical event: same topic, key, body (but the id) and headers. */
  public static void assertEquivalent(
      ConsumerRecord<String, String> polling, ConsumerRecord<String, String> cdc) {
    assertThat(cdc.topic()).isEqualTo(polling.topic());
    assertThat(cdc.key()).isEqualTo(polling.key().replace("polling", "cdc"));
    assertThat(withoutIdentity(cdc)).isEqualTo(withoutIdentity(polling));
    assertThat(header(cdc, "eventType")).isEqualTo(header(polling, "eventType"));
    assertThat(header(cdc, "schemaVersion")).isEqualTo(header(polling, "schemaVersion"));
    // each run has its own trace, so only the presence of one is comparable
    assertThat(traceId(cdc)).isNotNull();
    assertThat(traceId(polling)).isNotNull();
  }

  /** The body minus what differs by construction: ids and trace, which belong to the run. */
  private static JsonNode withoutIdentity(ConsumerRecord<String, String> record) {
    var envelope = (ObjectNode) JSON.readTree(record.value());
    envelope.remove("eventId");
    envelope.remove("aggregateId");
    envelope.remove("traceparent");
    return envelope;
  }

  /**
   * The trace the record belongs to. The relay stamps a header (a child span of the stored trace);
   * Debezium cannot, so the trace travels in the body and the consumers fall back to it.
   */
  static String traceId(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader("traceparent");
    return traceId(
        header != null
            ? new String(header.value(), StandardCharsets.UTF_8)
            : JSON.readTree(record.value()).get("traceparent").asString());
  }

  static String traceId(String traceparent) {
    return traceparent == null ? null : traceparent.split("-")[1];
  }

  static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    assertThat(header).as("header %s", name).isNotNull();
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
