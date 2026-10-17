package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Instant;
import java.util.List;

/** {@code changedFields} are snapshot field names that differ from the previous state, sorted. */
public record ProcurementUpdated(
    String aggregateId,
    Instant occurredAt,
    ProcurementSnapshotView snapshot,
    List<String> changedFields)
    implements ProcurementEvent {

  public ProcurementUpdated {
    changedFields = List.copyOf(changedFields);
  }
}
