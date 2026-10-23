package br.dev.miguelsanzbr.radar.ops;

/**
 * The runtime switches of the application. Each name is a feature declared under {@code
 * togglz.features} in application.properties, where the old property switch is the initial state;
 * the operator flips it at {@code /ops/togglz} and the state is kept in {@code ops.togglz_feature}.
 */
public enum Flag {
  INCREMENTAL_SYNC,
  CLOSE_EXPIRED,
  BACKFILL_SCHEDULED,
  ALERT_DIGEST,
  MAX_SAVED_SEARCHES_LIMIT
}
