package br.dev.miguelsanzbr.radar.identity.application.port;

import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import java.time.Instant;
import java.util.Optional;

public interface ApiKeys {

  enum Result {
    ADDED,
    LIMIT_REACHED,
    /** The owner already has a key (active or revoked) with this prefix. */
    DUPLICATE
  }

  /** Stores the key unless its owner already has {@code max} active ones; atomic per owner. */
  Result addWithinLimit(ApiKey key, int max);

  Optional<ApiKey> findActive(String keyHash);

  /** Revokes the active keys of the owner with this prefix; false when there was none. */
  boolean revoke(String ownerSubject, String prefix, Instant at);
}
