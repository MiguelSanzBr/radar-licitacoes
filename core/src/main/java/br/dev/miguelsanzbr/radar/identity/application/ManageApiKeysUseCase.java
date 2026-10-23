package br.dev.miguelsanzbr.radar.identity.application;

import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKeyLimitExceededException;
import br.dev.miguelsanzbr.radar.identity.domain.Tier;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.util.random.RandomGenerator;

public class ManageApiKeysUseCase {

  private static final int ATTEMPTS = 5;

  private final ApiKeys keys;
  private final int maxActive;
  private final Clock clock;
  private final UuidV7 ids;
  private final RandomGenerator random;

  public ManageApiKeysUseCase(
      ApiKeys keys, int maxActive, Clock clock, UuidV7 ids, RandomGenerator random) {
    this.keys = keys;
    this.maxActive = maxActive;
    this.clock = clock;
    this.ids = ids;
    this.random = random;
  }

  /**
   * Only the free tier is issued; pro keys are granted by hand in the database. A prefix the owner
   * already has (a 1 in 4 billion draw) is drawn again.
   */
  public ApiKey.Issued issue(String ownerSubject) {
    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      var issued = ApiKey.issue(ids.next(), ownerSubject, Tier.FREE, clock.instant(), random);
      switch (keys.addWithinLimit(issued.key(), maxActive)) {
        case ADDED -> {
          return issued;
        }
        case LIMIT_REACHED -> throw new ApiKeyLimitExceededException(maxActive);
        case DUPLICATE -> {
          // draw again
        }
      }
    }
    throw new IllegalStateException("no free api key prefix after " + ATTEMPTS + " draws");
  }

  public void revoke(String ownerSubject, String prefix) {
    if (!keys.revoke(ownerSubject, prefix, clock.instant())) {
      throw new ApiKeyNotFoundException();
    }
  }
}
