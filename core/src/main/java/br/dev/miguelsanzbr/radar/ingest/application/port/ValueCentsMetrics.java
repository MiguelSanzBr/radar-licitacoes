package br.dev.miguelsanzbr.radar.ingest.application.port;

/** Progress of the backfill of the estimated value cents. */
public interface ValueCentsMetrics {

  /** Rows still waiting for their cents. */
  void pending(long rows);
}
