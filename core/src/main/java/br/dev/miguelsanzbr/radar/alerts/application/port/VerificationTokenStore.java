package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenStore {

  void save(byte[] tokenHash, UUID subscriberId, Instant expiresAt);

  /** Marks an unused, unexpired token as used and returns its subscriber; single use. */
  Optional<UUID> consume(byte[] tokenHash, Instant now);

  void deleteBySubscriber(UUID subscriberId);
}
