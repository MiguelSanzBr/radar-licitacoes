package br.dev.miguelsanzbr.radar.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.identity.application.Fakes.MemoryApiKeys;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimitMetrics;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimiter;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;
import br.dev.miguelsanzbr.radar.identity.domain.RateLimits;
import br.dev.miguelsanzbr.radar.identity.domain.Tier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RateLimitUseCaseTest {

  private static final RandomGenerator RANDOM = RandomGenerator.of("L64X128MixRandom");
  private static final Decision ALLOWED = new Decision(true, 60, 59, 1, 0);
  private static final Decision EXHAUSTED = new Decision(false, 30, 0, 60, 2);

  private final MemoryApiKeys keys = new MemoryApiKeys();
  private final List<String> taken = new ArrayList<>();
  private final List<Integer> capacities = new ArrayList<>();
  private final List<RateLimiterUnavailableException> failures = new ArrayList<>();
  private RateLimiterUnavailableException down;
  private Decision answer = ALLOWED;
  private final RateLimiter limiter =
      (bucket, capacity) -> {
        if (down != null) {
          throw down;
        }
        taken.add(bucket);
        capacities.add(capacity);
        return answer;
      };
  private final RateLimitMetrics metrics = failures::add;
  private final RateLimitUseCase use =
      new RateLimitUseCase(keys, limiter, new RateLimits(60, 600, 30), metrics);

  private ApiKey.Issued store(Tier tier) {
    var issued =
        ApiKey.issue(
            UUID.randomUUID(), "kc-1", tier, Instant.parse("2026-10-22T12:00:00Z"), RANDOM);
    keys.active.add(issued.key());
    return issued;
  }

  @Test
  void aCallerWithoutAKeyIsMeteredByAddressAtTheAnonymousLimit() {
    assertThat(use.check(null, "203.0.113.9")).contains(ALLOWED);

    assertThat(taken).containsExactly("ip:203.0.113.9");
    assertThat(capacities).containsExactly(30);
  }

  @Test
  void aKeyIsMeteredByItsOwnBucketAtItsTierLimit() {
    var free = store(Tier.FREE);
    var pro = store(Tier.PRO);

    use.check(free.secret(), "203.0.113.9");
    use.check(pro.secret(), "203.0.113.9");

    assertThat(taken).containsExactly("key:" + free.key().id(), "key:" + pro.key().id());
    assertThat(capacities).containsExactly(60, 600);
  }

  @Test
  void aRevokedKeyIsRefusedAndChargedToTheAddressBucket() {
    var issued = store(Tier.FREE);
    keys.revoke("kc-1", issued.key().prefix(), Instant.now());

    assertThatThrownBy(() -> use.check(issued.secret(), "203.0.113.9"))
        .isInstanceOf(InvalidApiKeyException.class);

    assertThat(taken).containsExactly("ip:203.0.113.9");
    assertThat(capacities).containsExactly(30);
  }

  @Test
  void aWellFormedKeyThatDoesNotExistIsRefusedAndChargedToTheAddressBucket() {
    var unknown = ApiKey.issue(UUID.randomUUID(), "kc-9", Tier.FREE, Instant.now(), RANDOM);

    assertThatThrownBy(() -> use.check(unknown.secret(), "203.0.113.9"))
        .isInstanceOf(InvalidApiKeyException.class);

    assertThat(taken).containsExactly("ip:203.0.113.9");
  }

  @Test
  void aMalformedKeyNeverReachesTheDatabaseButIsStillCharged() {
    var lookups = new ArrayList<String>();
    var spying =
        new RateLimitUseCase(
            new MemoryApiKeys() {
              @Override
              public Optional<ApiKey> findActive(String keyHash) {
                lookups.add(keyHash);
                return super.findActive(keyHash);
              }
            },
            limiter,
            new RateLimits(60, 600, 30),
            metrics);

    assertThatThrownBy(() -> spying.check("", "203.0.113.9"))
        .isInstanceOf(InvalidApiKeyException.class);
    assertThatThrownBy(() -> spying.check("rk_00000000_nope", "203.0.113.9"))
        .isInstanceOf(InvalidApiKeyException.class);

    assertThat(lookups).isEmpty();
    assertThat(taken).containsExactly("ip:203.0.113.9", "ip:203.0.113.9");
  }

  @Test
  void invalidKeysFromAnExhaustedAddressAreTurnedAwayWith429NotAnother401() {
    answer = EXHAUSTED;

    assertThat(use.check("rk_00000000_nope", "203.0.113.9")).contains(EXHAUSTED);
  }

  @Test
  void anInvalidKeyIsStillRefusedWhenTheLimiterIsDown() {
    down = new RateLimiterUnavailableException(new IllegalStateException("redis down"));

    assertThatThrownBy(() -> use.check("rk_00000000_nope", "203.0.113.9"))
        .isInstanceOf(InvalidApiKeyException.class);
    assertThat(failures).containsExactly(down);
  }

  @Test
  void whenTheLimiterIsDownTheRequestPassesUnmeteredAndTheFailureIsReported() {
    down = new RateLimiterUnavailableException(new IllegalStateException("redis down"));

    assertThat(use.check(null, "203.0.113.9")).isEqualTo(Optional.empty());

    assertThat(failures).containsExactly(down);
  }
}
