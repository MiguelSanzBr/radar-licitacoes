package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore;
import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore.Cursor;
import br.dev.miguelsanzbr.radar.ingest.application.port.ValueCentsMetrics;

/**
 * Backfill of {@code estimated_value_cents} (expand step of the schema change, ADR-0010): walks the
 * primary keys forward in batches, one short transaction each, so no batch holds locks for long and
 * no batch rescans the rows an earlier one filled. Rows written after the expand step already carry
 * their cents (the trigger), so a single pass reaches every old row.
 */
public final class EstimatedValueCentsBackfill {

  private final EstimatedValueCentsStore store;
  private final ValueCentsMetrics metrics;
  private final int batchSize;

  public EstimatedValueCentsBackfill(
      EstimatedValueCentsStore store, ValueCentsMetrics metrics, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1");
    }
    this.store = store;
    this.metrics = metrics;
    this.batchSize = batchSize;
  }

  /** Fills until no row is left (or a pass reaches the end) and returns how many it filled. */
  public long run() {
    if (!store.hasPending()) {
      metrics.pending(0);
      return 0;
    }
    var remaining = store.pending();
    metrics.pending(remaining);
    var filled = 0L;
    var cursor = Cursor.START;
    while (true) {
      var batch = store.fillBatch(cursor, batchSize);
      if (batch.rows() == 0) {
        break;
      }
      filled += batch.rows();
      metrics.pending(Math.max(0, remaining - filled));
      if (batch.rows() < batchSize) {
        break;
      }
      cursor = batch.last();
    }
    // the exact count: rows may have arrived or been locked since the first one
    metrics.pending(store.pending());
    return filled;
  }
}
