package br.dev.miguelsanzbr.radar.ingest.application.port;

/** The runtime switches the ingest context reads; the adapter decides where they live. */
public interface FeatureFlags {

  /**
   * True once the estimated value is read from {@code estimated_value_cents} (the third step of the
   * expand-contract sequence, ADR-0010). Writes do not depend on it.
   */
  boolean readValueFromCents();
}
