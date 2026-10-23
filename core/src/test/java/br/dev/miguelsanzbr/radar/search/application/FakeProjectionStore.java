package br.dev.miguelsanzbr.radar.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Records what the projector hands to the store and answers with a fixed outcome per event. */
class FakeProjectionStore implements ProjectionStore {

  final List<Long> offsets = new ArrayList<>();
  final List<List<OffsetEvent>> batches = new ArrayList<>();
  int resets;
  private final Function<ProcurementEvent, ProjectionOutcome> outcomes;

  FakeProjectionStore(ProjectionOutcome outcome) {
    this(event -> outcome);
  }

  FakeProjectionStore(Function<ProcurementEvent, ProjectionOutcome> outcomes) {
    this.outcomes = outcomes;
  }

  @Override
  public ProjectionOutcome project(ProcurementEvent event, long offset) {
    offsets.add(offset);
    return outcomes.apply(event);
  }

  @Override
  public List<ProjectionOutcome> projectAll(List<OffsetEvent> batch) {
    batches.add(batch);
    return batch.stream().map(e -> outcomes.apply(e.event())).toList();
  }

  @Override
  public void reset() {
    resets++;
  }
}
