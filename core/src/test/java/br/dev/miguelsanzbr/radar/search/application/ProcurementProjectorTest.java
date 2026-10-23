package br.dev.miguelsanzbr.radar.search.application;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProcurementProjectorTest {

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
  private final Clock clock = Clock.fixed(T0.plusSeconds(90), ZoneOffset.UTC);

  private FakeProjectionStore store;

  private ProcurementProjector projector(ProjectionOutcome outcome) {
    store = new FakeProjectionStore(outcome);
    return new ProcurementProjector(store, metrics, clock);
  }

  @Test
  void anAppliedEventRecordsItsLagSinceOccurrence() {
    var event = discovered("a", view(id(1), "object", null, 1), 1);

    var outcome = projector(ProjectionOutcome.APPLIED).project(event, 42);

    assertThat(outcome).isEqualTo(ProjectionOutcome.APPLIED);
    assertThat(store.offsets).containsExactly(42L);
    assertThat(lags).containsExactly(Duration.ofSeconds(90));
  }

  @ParameterizedTest
  @EnumSource(
      value = ProjectionOutcome.class,
      names = {"DUPLICATE", "DEFERRED"})
  void anEventThatChangedNothingRecordsNoLag(ProjectionOutcome result) {
    var event = discovered("a", view(id(1), "object", null, 1), 1);

    assertThat(projector(result).project(event, 7)).isEqualTo(result);
    assertThat(lags).isEmpty();
  }

  @Test
  void onlyADeferredEventIsCounted() {
    var event = discovered("a", view(id(1), "object", null, 1), 1);

    projector(ProjectionOutcome.APPLIED).project(event, 1);
    projector(ProjectionOutcome.DUPLICATE).project(event, 2);
    assertThat(deferred).isZero();

    projector(ProjectionOutcome.DEFERRED).project(event, 3);
    assertThat(deferred).isEqualTo(1);
  }

  @Test
  void aClockBehindTheProducerNeverRecordsANegativeLag() {
    var early = Clock.fixed(T0.minusSeconds(5), ZoneOffset.UTC);
    var projector =
        new ProcurementProjector(
            new FakeProjectionStore(ProjectionOutcome.APPLIED), metrics, early);

    projector.project(discovered("a", view(id(1), "object", null, 1), 1), 1);

    assertThat(lags).containsExactly(Duration.ZERO);
  }
}
