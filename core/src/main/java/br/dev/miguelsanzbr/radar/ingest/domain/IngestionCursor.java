package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Where the incremental sync of one modality stands: {@code watermark} is the newest PNCP update
 * time processed. Each window starts one {@code overlap} before it, so updates that reach the PNCP
 * listing late are still read; the excess is absorbed by the idempotent processing.
 */
public record IngestionCursor(
    int modalityId, Instant watermark, Duration overlap, Instant updatedAt) {

  /** The span of PNCP update times one sync asks for. */
  public record Window(Instant start, Instant end) {}

  public static IngestionCursor initial(
      int modalityId, Instant now, Duration lookback, Duration overlap) {
    return new IngestionCursor(modalityId, now.minus(lookback), overlap, now);
  }

  public Window nextWindow(Instant now) {
    return new Window(watermark.minus(overlap), now);
  }

  /** The watermark never moves backwards: an earlier instant is a bug in the caller. */
  public IngestionCursor advance(Instant newWatermark, Clock clock) {
    if (newWatermark.isBefore(watermark)) {
      throw new IllegalArgumentException(
          "watermark cannot move back from " + watermark + " to " + newWatermark);
    }
    return new IngestionCursor(modalityId, newWatermark, overlap, clock.instant());
  }
}
