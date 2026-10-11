package br.dev.miguelsanzbr.radar.shared;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Common wrapper published for every domain event; {@code traceparent} may be null. */
public record EventEnvelope<P>(
    UUID eventId,
    String eventType,
    int schemaVersion,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    String traceparent,
    P payload) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1: " + schemaVersion);
    }
    if (aggregateVersion < 0) {
      throw new IllegalArgumentException("aggregateVersion must be >= 0: " + aggregateVersion);
    }
  }

  public static <P extends DomainEvent> EventEnvelope<P> wrap(
      P event,
      String aggregateType,
      long aggregateVersion,
      int schemaVersion,
      String traceparent,
      UuidV7 ids) {
    return new EventEnvelope<>(
        ids.next(),
        event.eventType(),
        schemaVersion,
        aggregateType,
        event.aggregateId(),
        aggregateVersion,
        event.occurredAt(),
        traceparent,
        event);
  }
}
