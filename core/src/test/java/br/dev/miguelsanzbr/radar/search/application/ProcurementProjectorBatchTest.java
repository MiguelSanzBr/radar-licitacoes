package br.dev.miguelsanzbr.radar.search.application;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcurementProjectorBatchTest {

  private final List<Duration> lags = new ArrayList<>();
  private int deferred;
  private final ProjectionMetrics metrics =
      new ProjectionMetrics() {
        @Override
        public void lag(Duration lag) {
          lags.add(lag);
        }

        @Override
        public void deferred() {
          deferred++;
        }
      };

  private final ProcurementEvent first = discovered("a", view(id(1), "object", null, 1), 1);
  private final ProcurementEvent second = closed("b", id(2), 2);
  private final List<OffsetEvent> batch =
      List.of(new OffsetEvent(first, 10), new OffsetEvent(second, 11));

  @Test
  void aBatchGoesToTheStoreInOneCallAndReturnsTheOutcomesInOrder() {
    var store =
        new FakeProjectionStore(
            event -> event == first ? ProjectionOutcome.APPLIED : ProjectionOutcome.DUPLICATE);
    var projector = new ProcurementProjector(store, metrics, Clock.fixed(T0, ZoneOffset.UTC));

    var outcomes = projector.projectAll(batch);

    assertThat(outcomes).containsExactly(ProjectionOutcome.APPLIED, ProjectionOutcome.DUPLICATE);
    assertThat(store.batches).containsExactly(batch);
  }

  @Test
  void aBatchCountsDeferredEventsButNeverRecordsLag() {
    var store = new FakeProjectionStore(ProjectionOutcome.DEFERRED);
    var projector = new ProcurementProjector(store, metrics, Clock.fixed(T0, ZoneOffset.UTC));

    projector.projectAll(batch);

    assertThat(deferred).isEqualTo(2);
    // why: a replay projects old events, and their age is not lag
    assertThat(lags).isEmpty();
  }

  @Test
  void resetGoesToTheStore() {
    var store = new FakeProjectionStore(ProjectionOutcome.APPLIED);

    new ProcurementProjector(store, metrics, Clock.fixed(T0, ZoneOffset.UTC)).reset();

    assertThat(store.resets).isEqualTo(1);
  }
}
