package br.dev.miguelsanzbr.radar.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore;
import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore.Batch;
import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore.Cursor;
import br.dev.miguelsanzbr.radar.ingest.application.port.ValueCentsMetrics;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class EstimatedValueCentsBackfillTest {

  private static final Instant AT = Instant.parse("2026-10-21T12:00:00Z");

  /** Keys 'k01', 'k02'...: rows still to fill; pending() answers the script, then the truth. */
  private static final class FakeStore implements EstimatedValueCentsStore {

    final List<String> todo = new ArrayList<>();
    final List<Cursor> cursors = new ArrayList<>();
    final List<Integer> limits = new ArrayList<>();
    final Queue<Long> pendingScript = new ArrayDeque<>();
    int pendingCalls;

    FakeStore(int rows) {
      for (var i = 1; i <= rows; i++) {
        todo.add("k%02d".formatted(i));
      }
    }

    @Override
    public boolean hasPending() {
      return !todo.isEmpty();
    }

    @Override
    public long pending() {
      pendingCalls++;
      return pendingScript.isEmpty() ? todo.size() : pendingScript.remove();
    }

    @Override
    public Batch fillBatch(Cursor after, int limit) {
      cursors.add(after);
      limits.add(limit);
      var batch = todo.stream().filter(k -> k.compareTo(after.number()) > 0).limit(limit).toList();
      todo.removeAll(batch);
      return batch.isEmpty()
          ? new Batch(0, after)
          : new Batch(batch.size(), new Cursor(batch.getLast(), AT));
    }
  }

  private static final class RecordingMetrics implements ValueCentsMetrics {

    final List<Long> reported = new ArrayList<>();

    @Override
    public void pending(long rows) {
      reported.add(rows);
    }
  }

  private final RecordingMetrics metrics = new RecordingMetrics();

  @Test
  void fillsEveryPendingRowInBatchesWalkingTheKeysForward() {
    var store = new FakeStore(7);

    var filled = new EstimatedValueCentsBackfill(store, metrics, 3).run();

    assertThat(filled).isEqualTo(7);
    assertThat(store.todo).isEmpty();
    assertThat(store.limits).containsOnly(3);
    assertThat(store.cursors).extracting(Cursor::number).containsExactly("", "k03", "k06");
    assertThat(store.cursors.getFirst()).isEqualTo(Cursor.START);
  }

  @Test
  void reportsTheRowsRemainingAfterEachBatchAndTheExactCountAtTheEnd() {
    var store = new FakeStore(7);

    new EstimatedValueCentsBackfill(store, metrics, 3).run();

    assertThat(metrics.reported).containsExactly(7L, 4L, 1L, 0L, 0L);
  }

  @Test
  void aBatchThatIsFullAskedOnceMoreAndAnEmptyAnswerStopsTheRun() {
    var store = new FakeStore(6);

    new EstimatedValueCentsBackfill(store, metrics, 3).run();

    assertThat(store.limits).hasSize(3);
  }

  @Test
  void aShortBatchStopsTheRunWithoutAnotherQuery() {
    var store = new FakeStore(2);

    new EstimatedValueCentsBackfill(store, metrics, 5).run();

    assertThat(store.limits).hasSize(1);
  }

  @Test
  void nothingPendingTouchesNoRowAndReportsZero() {
    var store = new FakeStore(0);

    var filled = new EstimatedValueCentsBackfill(store, metrics, 5).run();

    assertThat(filled).isZero();
    assertThat(store.cursors).isEmpty();
    assertThat(store.pendingCalls).isZero();
    assertThat(metrics.reported).containsExactly(0L);
  }

  @Test
  void theRemainingNeverGoesBelowZeroWhenRowsArrivedAfterTheFirstCount() {
    var store = new FakeStore(5);
    store.pendingScript.add(2L);
    store.pendingScript.add(3L);

    new EstimatedValueCentsBackfill(store, metrics, 5).run();

    assertThat(metrics.reported).containsExactly(2L, 0L, 3L);
  }

  @Test
  void aNonPositiveBatchSizeIsRejected() {
    var store = new FakeStore(0);

    assertThatThrownBy(() -> new EstimatedValueCentsBackfill(store, metrics, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batchSize");
  }
}
