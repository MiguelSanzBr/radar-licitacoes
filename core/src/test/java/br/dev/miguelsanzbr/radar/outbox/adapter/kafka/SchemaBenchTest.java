package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Size of the record and time to serialize it, JSON against Avro, without JMH. Indicative only: one
 * JVM, one warm-up pass, the registry is in memory (no network round trip) and the time covers the
 * value serializer alone. Run with {@code ./mvnw -Pbench test -pl core -am -Dtest=SchemaBenchTest};
 * the numbers go to the standard output and to docs/benchmarks/schema/evolution.md.
 */
@Tag("bench")
class SchemaBenchTest {

  private static final int EVENTS = 10_000;

  private static long nanos(Runnable work) {
    var start = System.nanoTime();
    work.run();
    return System.nanoTime() - start;
  }

  @Test
  void sizeAndSerializationTimeOfAJsonAndAnAvroRecord() {
    var wire = new AvroWire();
    var json = new EnvelopeSerializer();
    json.configure(Map.of(), false);
    var avro = EventSamples.updated();
    var topic = "radar.procurement.v1";

    for (var sample : EventSamples.all().values()) {
      var jsonBytes = sample.getBytes(StandardCharsets.UTF_8).length;
      var avroBytes = wire.encode(sample, new RecordHeaders()).length;
      System.out.printf(
          "SCHEMA-SIZE %s json=%d avro=%d ratio=%.2f%n",
          EventSamples.all().entrySet().stream()
              .filter(e -> e.getValue().equals(sample))
              .findFirst()
              .orElseThrow()
              .getKey(),
          jsonBytes,
          avroBytes,
          (double) avroBytes / jsonBytes);
    }

    // warm-up, so the JIT and the schema cache are not in the numbers
    for (var i = 0; i < 2_000; i++) {
      json.serialize(topic, new RecordHeaders(), avro);
      wire.encode(avro, new RecordHeaders());
    }
    var jsonNanos =
        nanos(
            () -> {
              for (var i = 0; i < EVENTS; i++) {
                json.serialize(topic, new RecordHeaders(), avro);
              }
            });
    var avroNanos =
        nanos(
            () -> {
              for (var i = 0; i < EVENTS; i++) {
                wire.encode(avro, new RecordHeaders());
              }
            });
    var decoded = wire.encode(avro, new RecordHeaders());
    var decodeNanos =
        nanos(
            () -> {
              for (var i = 0; i < EVENTS; i++) {
                wire.decode(topic, new RecordHeaders(), decoded);
              }
            });
    System.out.printf(
        "SCHEMA-TIME events=%d json_serialize_us=%.2f avro_serialize_us=%.2f avro_decode_us=%.2f%n",
        EVENTS, jsonNanos / 1e3 / EVENTS, avroNanos / 1e3 / EVENTS, decodeNanos / 1e3 / EVENTS);

    assertThat(decoded.length).isLessThan(avro.getBytes(StandardCharsets.UTF_8).length);
  }
}
