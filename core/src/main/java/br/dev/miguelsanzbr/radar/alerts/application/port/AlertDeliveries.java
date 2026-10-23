package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.time.Instant;
import java.util.UUID;

/** The delivery row of a (saved search, procurement) pair: its uniqueness is the deduplication. */
public interface AlertDeliveries {

  /** True when the pair was new and is now stored, false when it already existed. */
  boolean insertIfAbsent(UUID savedSearchId, String procurementId, Instant matchedAt);
}
