package br.dev.miguelsanzbr.radar.ingest.application.port;

import java.time.Instant;

/** The notices whose estimated value has no cents yet (the backfill of the expand step). */
public interface EstimatedValueCentsStore {

  /** Whether any row has the numeric value and no cents: cheap, the check of an idle tick. */
  boolean hasPending();

  /** How many rows have the numeric value and no cents. */
  long pending();

  /**
   * Fills the cents of up to {@code limit} pending rows whose key is after {@code after}, in key
   * order, in one short transaction. The batch carries the last key it filled, or {@code after}
   * when there was nothing.
   */
  Batch fillBatch(Cursor after, int limit);

  /** The primary key of a notice: both parts, because a number can repeat in other months. */
  record Cursor(String number, Instant publishedAt) {

    public static final Cursor START = new Cursor("", Instant.EPOCH);
  }

  record Batch(int rows, Cursor last) {}
}
