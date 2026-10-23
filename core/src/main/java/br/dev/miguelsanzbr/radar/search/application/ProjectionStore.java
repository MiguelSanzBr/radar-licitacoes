package br.dev.miguelsanzbr.radar.search.application;

import java.util.List;

/** Read side storage of the projector. */
public interface ProjectionStore {

  /**
   * Records the event in the inbox and applies it, in one transaction: either both happen or
   * neither. {@code offset} is the Kafka offset of the record, kept in the checkpoint.
   */
  ProjectionOutcome project(ProcurementEvent event, long offset);

  /**
   * {@link #project} for each event, in order, all in one transaction: an event that is deferred
   * leaves no trace and does not undo the others.
   */
  List<ProjectionOutcome> projectAll(List<OffsetEvent> batch);

  /** Empties the read model and the inbox of the projector, in one transaction. */
  void reset();
}
