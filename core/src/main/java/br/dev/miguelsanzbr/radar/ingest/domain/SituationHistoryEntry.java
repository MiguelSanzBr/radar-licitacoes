package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Instant;
import java.util.Objects;

/** One step of the situation history; {@code fromSituationId} is null on discovery. */
public record SituationHistoryEntry(
    Integer fromSituationId,
    int toSituationId,
    boolean unexpected,
    Instant pncpUpdatedAt,
    Instant recordedAt) {

  public SituationHistoryEntry {
    Objects.requireNonNull(pncpUpdatedAt, "pncpUpdatedAt");
    Objects.requireNonNull(recordedAt, "recordedAt");
  }
}
