package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The part of a {@code radar.procurement.v1} envelope the matcher reads. Alerts keeps its own copy
 * of the shape so it does not import the types of the contexts that publish and project it.
 */
record ProcurementEnvelope(
    UUID eventId, String eventType, Instant occurredAt, String traceparent, Payload payload) {

  ProcurementEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  record Payload(Snapshot snapshot, List<String> changedFields) {}

  /** What the contract requires of a snapshot is required here, so a broken one fails on parse. */
  record Snapshot(
      String id,
      String objectDescription,
      Organ organ,
      Unit unit,
      Modality modality,
      String estimatedValue,
      ProposalWindow proposalWindow) {

    Snapshot {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(objectDescription, "objectDescription");
      Objects.requireNonNull(organ, "organ");
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(modality, "modality");
    }
  }

  record Organ(String name) {

    Organ {
      Objects.requireNonNull(name, "organ name");
    }
  }

  record Unit(String name, String uf, String municipalityName, String ibgeCode) {

    Unit {
      Objects.requireNonNull(name, "unit name");
    }
  }

  record Modality(Integer id) {

    Modality {
      Objects.requireNonNull(id, "modality id");
    }
  }

  record ProposalWindow(Instant closingAt) {}

  /** The facts of the snapshot; a matchable event without one is malformed. */
  ProcurementFacts facts() {
    var snapshot = payload.snapshot();
    if (snapshot == null) {
      throw new MalformedEventException("event " + eventId + " has no snapshot");
    }
    var window = snapshot.proposalWindow();
    return new ProcurementFacts(
        snapshot.id(),
        snapshot.objectDescription(),
        snapshot.organ().name(),
        snapshot.unit().name(),
        snapshot.unit().uf(),
        snapshot.unit().ibgeCode(),
        snapshot.unit().municipalityName(),
        snapshot.modality().id(),
        snapshot.estimatedValue() == null ? null : Money.of(snapshot.estimatedValue()),
        window == null ? null : window.closingAt());
  }

  List<String> changedFields() {
    return payload.changedFields() == null ? List.of() : payload.changedFields();
  }
}
