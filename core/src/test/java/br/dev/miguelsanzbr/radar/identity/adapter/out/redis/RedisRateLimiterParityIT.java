package br.dev.miguelsanzbr.radar.identity.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.identity.domain.RateLimits;
import br.dev.miguelsanzbr.radar.identity.domain.TokenBucketModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Lua script answers exactly like the Java model of the bucket, whatever the sequence. */
class RedisRateLimiterParityIT {

  private static final StringRedisTemplate REDIS = SharedRedisTemplate.open();
  private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");

  @AfterContainer
  static void closeConnection() {
    SharedRedisTemplate.close(REDIS);
  }

  @Provide
  Arbitrary<Integer> capacities() {
    return Arbitraries.integers().between(1, 120);
  }

  /** Mostly forward, sometimes a little back (skewed clocks of two instances). */
  @Provide
  Arbitrary<List<Long>> gaps() {
    return Arbitraries.longs().between(-2_000, 90_000).list().ofMinSize(1).ofMaxSize(60);
  }

  @Property(tries = 40)
  void theScriptAnswersExactlyLikeTheModel(
      @ForAll("capacities") int capacity, @ForAll("gaps") List<Long> gaps) {
    var bucket = "parity:" + UUID.randomUUID();
    var model = new TokenBucketModel(capacity, RateLimits.WINDOW_MS);
    var moving = new MutableClock(START);
    var scripted = new RedisRateLimiter(REDIS, moving);

    for (var gap : gaps) {
      moving.advance(Duration.ofMillis(gap));
      assertThat(scripted.take(bucket, capacity)).isEqualTo(model.take(moving.millis()));
    }
  }
}
