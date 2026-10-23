package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.avro.Schema;

/**
 * The Avro schemas of the contracts module ({@code avro/*.avsc}), one record per event type; each
 * is registered alone, so each carries the whole envelope and its own payload.
 */
final class AvroSchemas {

  private static final List<String> EVENT_TYPES =
      List.of(
          "ProcurementDiscovered",
          "ProcurementUpdated",
          "ProcurementSituationChanged",
          "ProcurementClosed",
          "AlertMatched");

  private static final Map<String, Schema> BY_EVENT_TYPE =
      EVENT_TYPES.stream()
          .collect(
              Collectors.toUnmodifiableMap(
                  type -> type,
                  type ->
                      load(
                          "avro/"
                              + type.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)
                              + ".v1.avsc")));

  private AvroSchemas() {}

  /** The schema of an event type; an event type without one is rejected. */
  static Schema forEventType(String eventType) {
    var schema = BY_EVENT_TYPE.get(eventType);
    if (schema == null) {
      throw new IllegalArgumentException("no avro schema for event type " + eventType);
    }
    return schema;
  }

  /** The common header, for documentation and the parity test; it is never registered. */
  static Schema envelope() {
    return load("avro/envelope.avsc");
  }

  /** Every event schema, in the order of the events. */
  static List<Schema> all() {
    return EVENT_TYPES.stream().map(BY_EVENT_TYPE::get).toList();
  }

  static Schema load(String resource) {
    var in = AvroSchemas.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException("missing schema file " + resource);
    }
    return parse(in);
  }

  static Schema parse(InputStream in) {
    try (in) {
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
