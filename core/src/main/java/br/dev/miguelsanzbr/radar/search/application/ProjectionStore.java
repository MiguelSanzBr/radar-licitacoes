package br.dev.miguelsanzbr.radar.search.application;

/** Read side storage of the projector. */
public interface ProjectionStore {

  /**
   * Records the event in the inbox and applies it, in one transaction: either both happen or
   * neither. {@code offset} is the Kafka offset of the record, kept in the checkpoint.
   */
  ProjectionOutcome project(ProcurementEvent event, long offset);
}
