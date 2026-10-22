package br.dev.miguelsanzbr.radar.search.application;

import java.time.Clock;
import java.time.Duration;

/** Projects procurement events into the search read model, once per event. */
public class ProcurementProjector {

  private final ProjectionStore store;
  private final ProjectionMetrics metrics;
  private final Clock clock;

  public ProcurementProjector(ProjectionStore store, ProjectionMetrics metrics, Clock clock) {
    this.store = store;
    this.metrics = metrics;
    this.clock = clock;
  }

  public ProjectionOutcome project(ProcurementEvent event, long offset) {
    var outcome = store.project(event, offset);
    if (outcome == ProjectionOutcome.APPLIED) {
      var lag = Duration.between(event.occurredAt(), clock.instant());
      metrics.lag(lag.isNegative() ? Duration.ZERO : lag);
    }
    if (outcome == ProjectionOutcome.DEFERRED) {
      metrics.deferred();
    }
    return outcome;
  }
}
