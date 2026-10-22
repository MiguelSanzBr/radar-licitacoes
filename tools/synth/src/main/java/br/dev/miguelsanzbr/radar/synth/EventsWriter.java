package br.dev.miguelsanzbr.radar.synth;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Writes one {@code ProcurementDiscovered} envelope per line to a gzip file, in the shape the
 * outbox publishes (contracts module, {@code events/}). The event id is a UUID v7 of the event time
 * whose random part comes from the procurement id, so the file is byte-identical for a seed.
 */
enum EventsWriter {
  ; // no constants: a holder of static methods that cannot be instantiated

  static void write(Path file, Generator generator, long count) throws IOException {
    // an absolute path always has a parent unless it is the root, which is not a file to write
    Files.createDirectories(Objects.requireNonNull(file.toAbsolutePath().getParent()));
    try (var out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
      for (long i = 0; i < count; i++) {
        out.write((envelope(generator.next()) + "\n").getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  static String envelope(Synthetic row) {
    var envelope = Synthetic.JSON.createObjectNode();
    envelope.put("eventId", eventId(row).toString());
    envelope.put("eventType", "ProcurementDiscovered");
    envelope.put("schemaVersion", 1);
    envelope.put("aggregateType", "Procurement");
    envelope.put("aggregateId", row.id());
    envelope.put("aggregateVersion", 1);
    envelope.put("occurredAt", row.publishedAt().toString());
    envelope.putNull("traceparent");
    var payload = envelope.putObject("payload");
    payload.put("aggregateId", row.id());
    payload.put("occurredAt", row.publishedAt().toString());
    payload.set("snapshot", row.snapshot());
    return Synthetic.JSON.writeValueAsString(envelope);
  }

  private static UUID eventId(Synthetic row) {
    var random = UUID.nameUUIDFromBytes(row.id().getBytes(StandardCharsets.UTF_8));
    long millis = row.publishedAt().toEpochMilli();
    long high = (millis << 16) | 0x7000L | (random.getMostSignificantBits() & 0x0FFFL);
    long low = (random.getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(high, low);
  }
}
