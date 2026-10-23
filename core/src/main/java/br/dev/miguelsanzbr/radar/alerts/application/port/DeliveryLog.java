package br.dev.miguelsanzbr.radar.alerts.application.port;

import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the notifier reads and writes on the delivery rows the matcher created. Only subscribers who
 * can still receive mail (verified, not erased, search active) are ever visible here.
 */
public interface DeliveryLog {

  /** The row of an alert with who gets it, or empty when nobody can receive it any more. */
  record Delivery(Instant matchedAt, Instant sentAt, String email, String searchName) {}

  /** A DAILY alert waiting for the digest of {@code subscriberId}. */
  record Queued(UUID subscriberId, String email, String searchName, AlertMatched alert) {}

  Optional<Delivery> find(UUID savedSearchId, String procurementId);

  /**
   * Sets the sent time of a row once (a row already sent keeps its time) and drops what was kept
   * for the digest, which nothing needs after the send.
   */
  void markSent(UUID savedSearchId, String procurementId, Instant at);

  /** Stores what the digest shows for the alert's row, so the digest needs no other lookup. */
  void keepForDigest(AlertMatched alert);

  /** Unsent rows with their data kept, ordered by subscriber and match time. */
  List<Queued> unsentDigest();
}
