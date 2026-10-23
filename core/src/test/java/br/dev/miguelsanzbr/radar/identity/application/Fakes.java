package br.dev.miguelsanzbr.radar.identity.application;

import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** In-memory collaborators of the identity use cases. */
final class Fakes {

  /** Keeps the keys in a list; revoked keys are dropped, as only active ones are ever read. */
  static class MemoryApiKeys implements ApiKeys {

    final List<ApiKey> active = new ArrayList<>();
    int lastMax;

    @Override
    public Result addWithinLimit(ApiKey key, int max) {
      lastMax = max;
      var owned = active.stream().filter(k -> k.ownerSubject().equals(key.ownerSubject()));
      var mine = owned.toList();
      if (mine.stream().anyMatch(k -> k.prefix().equals(key.prefix()))) {
        return Result.DUPLICATE;
      }
      if (mine.size() >= max) {
        return Result.LIMIT_REACHED;
      }
      active.add(key);
      return Result.ADDED;
    }

    @Override
    public Optional<ApiKey> findActive(String keyHash) {
      return active.stream().filter(k -> k.keyHash().equals(keyHash)).findFirst();
    }

    @Override
    public boolean revoke(String owner, String prefix, Instant at) {
      return active.removeIf(k -> k.ownerSubject().equals(owner) && k.prefix().equals(prefix));
    }
  }

  private Fakes() {}
}
