package br.dev.miguelsanzbr.radar.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.identity.application.Fakes.MemoryApiKeys;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKeyLimitExceededException;
import br.dev.miguelsanzbr.radar.identity.domain.Tier;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ManageApiKeysUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final RandomGenerator RANDOM = RandomGenerator.of("L64X128MixRandom");

  private final MemoryApiKeys keys = new MemoryApiKeys();
  private final ManageApiKeysUseCase use =
      new ManageApiKeysUseCase(keys, 2, CLOCK, new UuidV7(CLOCK, RANDOM), RANDOM);

  @Test
  void issuesAFreeKeyToTheCallerAndStoresOnlyItsHash() {
    var issued = use.issue("kc-1");

    assertThat(issued.key().tier()).isEqualTo(Tier.FREE);
    assertThat(issued.key().ownerSubject()).isEqualTo("kc-1");
    assertThat(issued.key().createdAt()).isEqualTo(NOW);
    assertThat(keys.active).containsExactly(issued.key());
    assertThat(keys.active.getFirst().keyHash()).isEqualTo(ApiKey.hash(issued.secret()));
    assertThat(keys.lastMax).isEqualTo(2);
  }

  @Test
  void refusesAKeyBeyondTheLimitOfActiveKeysOfTheSubject() {
    use.issue("kc-1");
    use.issue("kc-1");

    assertThatThrownBy(() -> use.issue("kc-1"))
        .isInstanceOf(ApiKeyLimitExceededException.class)
        .hasMessageContaining("2");
    assertThat(use.issue("kc-2").key().ownerSubject()).isEqualTo("kc-2");
    assertThat(keys.active).hasSize(3);
  }

  @Test
  void revokingFreesASlot() {
    var first = use.issue("kc-1");
    use.issue("kc-1");

    use.revoke("kc-1", first.key().prefix());

    assertThat(use.issue("kc-1")).isNotNull();
  }

  @Test
  void revokingAnotherSubjectsKeyOrAnUnknownPrefixIsNotFound() {
    var issued = use.issue("kc-1");

    assertThatThrownBy(() -> use.revoke("kc-2", issued.key().prefix()))
        .isInstanceOf(ApiKeyNotFoundException.class);
    assertThatThrownBy(() -> use.revoke("kc-1", "00000000"))
        .isInstanceOf(ApiKeyNotFoundException.class);
    assertThat(keys.active).hasSize(1);
  }

  /** Repeats one value for the first calls, then moves on: two keys of the same prefix in a row. */
  private static final class StuckThenFresh implements RandomGenerator {
    private final int stuckCalls;
    private int calls;

    StuckThenFresh(int stuckCalls) {
      this.stuckCalls = stuckCalls;
    }

    @Override
    public long nextLong() {
      return calls++ < stuckCalls ? 42L : calls * 7919L;
    }
  }

  @Test
  void aPrefixTheOwnerAlreadyHasIsRegeneratedUntilItIsFree() {
    // nextBytes(4) + nextBytes(32) take five longs per key: the second key repeats the first
    var random = new StuckThenFresh(10);
    var use = new ManageApiKeysUseCase(keys, 5, CLOCK, new UuidV7(CLOCK, RANDOM), random);

    var first = use.issue("kc-1");
    var second = use.issue("kc-1");

    assertThat(second.key().prefix()).isNotEqualTo(first.key().prefix());
    assertThat(keys.active).containsExactly(first.key(), second.key());
  }

  @Test
  void givesUpWhenNoFreePrefixTurnsUp() {
    var use =
        new ManageApiKeysUseCase(
            keys, 5, CLOCK, new UuidV7(CLOCK, RANDOM), new StuckThenFresh(Integer.MAX_VALUE));
    use.issue("kc-1");

    assertThatThrownBy(() -> use.issue("kc-1")).isInstanceOf(IllegalStateException.class);
    assertThat(keys.active).hasSize(1);
  }
}
