package br.dev.miguelsanzbr.radar.search.application;

/** The consumer that keeps the read model current, which a replay must keep out of its way. */
public interface LiveProjection {

  boolean isRunning();

  /** Returns once the consumer no longer projects; the record in flight is finished first. */
  void stop();

  void start();
}
