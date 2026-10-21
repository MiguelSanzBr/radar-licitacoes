package br.dev.miguelsanzbr.radar.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One stored outbox row; {@code payload} is the JSON text exactly as stored. */
public record OutboxMessage(
    long id,
    UUID eventId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int schemaVersion,
    String payload,
    Map<String, Object> headers,
    Instant createdAt,
    int attempts) {

  public OutboxMessage {
    headers = Map.copyOf(headers);
  }
}
