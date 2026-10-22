package br.dev.miguelsanzbr.radar.search.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The events of the procurement topic that the read side projects, already decoded from the
 * envelope. {@code aggregateVersion} orders events of one procurement.
 */
public sealed interface ProcurementEvent {

  UUID eventId();

  String aggregateId();

  long aggregateVersion();

  Instant occurredAt();

  record Discovered(
      UUID eventId,
      String aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      ProcurementView snapshot)
      implements ProcurementEvent {
    public Discovered {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  record Updated(
      UUID eventId,
      String aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      ProcurementView snapshot)
      implements ProcurementEvent {
    public Updated {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  record SituationChanged(
      UUID eventId,
      String aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      Situation from,
      Situation to,
      Instant pncpUpdatedAt,
      boolean unexpected)
      implements ProcurementEvent {
    public SituationChanged {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }
  }

  record Closed(
      UUID eventId,
      String aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      Instant closingAt)
      implements ProcurementEvent {
    public Closed {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(aggregateId, "aggregateId");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }
}
