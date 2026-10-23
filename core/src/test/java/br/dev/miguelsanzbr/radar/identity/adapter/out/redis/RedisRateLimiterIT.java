package br.dev.miguelsanzbr.radar.identity.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Lua script against a real Redis, with the clock in the test's hands. */
class RedisRateLimiterIT {

  private static final StringRedisTemplate REDIS = SharedRedisTemplate.open();
  private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");

  private final MutableClock clock = new MutableClock(START);
  private final RedisRateLimiter limiter = new RedisRateLimiter(REDIS, clock);

  @AfterAll
  static void closeConnection() {
    SharedRedisTemplate.close(REDIS);
  }

  private static String bucket() {
    return "test:" + UUID.randomUUID();
  }

  @Test
  void theSixtyFirstRequestOfTheMinuteIsDeniedAndTheBucketRefillsOneTokenPerSecond() {
    var bucket = bucket();

    for (var i = 0; i < 60; i++) {
      assertThat(limiter.take(bucket, 60)).isEqualTo(new Decision(true, 60, 59 - i, i + 1, 0));
    }
    assertThat(limiter.take(bucket, 60)).isEqualTo(new Decision(false, 60, 0, 60, 1));

    clock.advance(Duration.ofSeconds(1));
    assertThat(limiter.take(bucket, 60)).isEqualTo(new Decision(true, 60, 0, 60, 0));
    assertThat(limiter.take(bucket, 60).allowed()).isFalse();
  }

  @Test
  void withoutAClockTheTimeIsRedisOwnSoInstancesCannotDisagree() {
    var real = new RedisRateLimiter(REDIS);
    var bucket = bucket();

    for (var i = 0; i < 60; i++) {
      assertThat(real.take(bucket, 60).allowed()).isTrue();
    }

    assertThat(real.take(bucket, 60).allowed()).isFalse();
    var stored = Long.parseLong((String) REDIS.opsForHash().get("rl:" + bucket, "ts"));
    assertThat(stored)
        .isBetween(System.currentTimeMillis() - 10_000, System.currentTimeMillis() + 10_000);
  }

  @Test
  void aBucketIdleForAMinuteIsFullAgain() {
    var bucket = bucket();
    for (var i = 0; i < 60; i++) {
      limiter.take(bucket, 60);
    }

    clock.advance(Duration.ofMinutes(1));

    assertThat(limiter.take(bucket, 60)).isEqualTo(new Decision(true, 60, 59, 1, 0));
  }

  @Test
  void bucketsDoNotShareTokens() {
    var first = bucket();
    var second = bucket();
    for (var i = 0; i < 30; i++) {
      limiter.take(first, 30);
    }

    assertThat(limiter.take(first, 30).allowed()).isFalse();
    assertThat(limiter.take(second, 30)).isEqualTo(new Decision(true, 30, 29, 2, 0));
  }

  @Test
  void theBucketExpiresOnceItWouldBeFullAnyway() {
    var bucket = bucket();

    limiter.take(bucket, 60);

    assertThat(REDIS.getExpire("rl:" + bucket, TimeUnit.MILLISECONDS)).isBetween(1L, 61_000L);
  }

  @Test
  void parallelRequestsNeverTakeMoreThanTheCapacity() throws Exception {
    var bucket = bucket();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(50)) {
      var results =
          IntStream.range(0, 50)
              .mapToObj(
                  i ->
                      pool.submit(
                          (Callable<Boolean>)
                              () -> {
                                start.await();
                                return limiter.take(bucket, 20).allowed();
                              }))
              .toList();
      start.countDown();
      var allowed = 0;
      for (var result : results) {
        allowed += result.get() ? 1 : 0;
      }

      assertThat(allowed).isEqualTo(20);
    }
  }

  @Test
  void aClockThatGoesBackNeitherRefillsNorBreaksTheBucket() {
    var bucket = bucket();
    for (var i = 0; i < 60; i++) {
      limiter.take(bucket, 60);
    }

    clock.advance(Duration.ofSeconds(-10));
    assertThat(limiter.take(bucket, 60).allowed()).isFalse();
    clock.advance(Duration.ofSeconds(11));
    assertThat(limiter.take(bucket, 60).allowed()).isTrue();
  }
}
