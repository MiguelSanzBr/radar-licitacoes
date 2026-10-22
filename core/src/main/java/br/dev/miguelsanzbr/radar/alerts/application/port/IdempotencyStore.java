package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {

  /** What a finished call stored; {@code sameRequest} compares its body hash with the asked one. */
  record Recorded(boolean sameRequest, int status, String body) {}

  /**
   * Drops the key if older than {@code expiredBefore}, then tries to take it. Returns false when
   * another call holds it; when that call is still open this blocks until it ends.
   */
  boolean claim(
      UUID subscriberId, String key, byte[] requestHash, Instant now, Instant expiredBefore);

  Optional<Recorded> find(UUID subscriberId, String key, byte[] requestHash);

  void complete(UUID subscriberId, String key, int status, String body);

  void deleteBySubscriber(UUID subscriberId);

  int purgeBefore(Instant cutoff);
}
