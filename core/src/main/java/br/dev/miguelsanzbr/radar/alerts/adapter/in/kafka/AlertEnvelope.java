package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The part of a {@code radar.alert.v1} envelope the notifier reads. What the contract requires is
 * required here, so a broken event fails on parse and goes to the dead-letter topic at once.
 */
record AlertEnvelope(UUID eventId, Instant occurredAt, String traceparent, Payload payload) {

  AlertEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
  }

  record Payload(
      UUID savedSearchId,
      UUID subscriberId,
      String procurementId,
      Frequency frequency,
      String objectDescription,
      String organName,
      String uf,
      String municipality,
      String estimatedValue,
      Instant proposalClosingAt,
      String detailPath) {

    Payload {
      Objects.requireNonNull(savedSearchId, "savedSearchId");
      Objects.requireNonNull(subscriberId, "subscriberId");
      Objects.requireNonNull(procurementId, "procurementId");
      Objects.requireNonNull(frequency, "frequency");
      Objects.requireNonNull(objectDescription, "objectDescription");
      Objects.requireNonNull(organName, "organName");
      Objects.requireNonNull(detailPath, "detailPath");
    }
  }

  AlertMatched alert() {
    var p = payload;
    return new AlertMatched(
        p.savedSearchId(),
        p.subscriberId(),
        p.procurementId(),
        p.frequency(),
        p.objectDescription(),
        p.organName(),
        p.uf(),
        p.municipality(),
        p.estimatedValue() == null ? null : Money.of(p.estimatedValue()),
        p.proposalClosingAt(),
        p.detailPath());
  }
}
