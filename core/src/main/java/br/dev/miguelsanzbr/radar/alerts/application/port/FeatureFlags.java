package br.dev.miguelsanzbr.radar.alerts.application.port;

/** The runtime switches the alerts use cases honour. */
@FunctionalInterface
public interface FeatureFlags {

  /** False lifts the limit of saved searches per subscriber. */
  boolean savedSearchLimitEnforced();
}
