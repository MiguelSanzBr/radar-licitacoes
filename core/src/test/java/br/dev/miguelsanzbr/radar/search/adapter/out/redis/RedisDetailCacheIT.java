package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.SharedRedis;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The cache against the real Redis: key, TTL and the namespace wipe. */
class RedisDetailCacheIT {

  private static StringRedisTemplate redis;

  /** The delegate: every id is found. */
  private final DetailReadModel database = id -> Optional.of(Details.full(id, "etag-" + id));

  private final RedisDetailCache cache =
      new RedisDetailCache(
          database, redis, Duration.ofHours(1), new SimpleMeterRegistry(), Clock.systemUTC());

  @BeforeAll
  static void open() {
    var factory =
        new LettuceConnectionFactory(
            SharedRedis.INSTANCE.getHost(), SharedRedis.INSTANCE.getMappedPort(6379));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
  }

  @AfterAll
  static void close() {
    ((LettuceConnectionFactory) redis.getConnectionFactory()).destroy();
  }

  private static String unique() {
    return "99999999000199-1-" + UUID.randomUUID() + "/2026";
  }

  @Test
  void theDetailIsStoredUnderTheVersionedKeyWithAnHourToLive() {
    var id = unique();

    cache.find(id);

    var key = "search:detail:v1:" + id;
    assertThat(redis.opsForValue().get(key)).contains("\"etag\":\"etag-" + id + "\"");
    assertThat(redis.getExpire(key)).isBetween(3_590L, 3_600L);
    redis.delete(key);
  }

  @Test
  void invalidatingDropsOnlyThatKeyAndAMissingKeyIsHarmless() {
    var kept = unique();
    var dropped = unique();
    cache.find(kept);
    cache.find(dropped);

    cache.invalidate(dropped);
    cache.invalidate(unique());

    assertThat(redis.hasKey("search:detail:v1:" + dropped)).isFalse();
    assertThat(redis.hasKey("search:detail:v1:" + kept)).isTrue();
    redis.delete("search:detail:v1:" + kept);
  }

  @Test
  void aBatchDropsExactlyTheKeysItNamesInOneCall() {
    var first = unique();
    var second = unique();
    var kept = unique();
    cache.find(first);
    cache.find(second);
    cache.find(kept);

    cache.invalidateAll(List.of(first, second, unique()));
    cache.invalidateAll(List.of());

    assertThat(redis.hasKey("search:detail:v1:" + first)).isFalse();
    assertThat(redis.hasKey("search:detail:v1:" + second)).isFalse();
    assertThat(redis.hasKey("search:detail:v1:" + kept)).isTrue();
    redis.delete("search:detail:v1:" + kept);
  }

  @Test
  void clearingRemovesTheWholeNamespaceInBatchesAndNothingElse() {
    var ids = new ArrayList<String>();
    for (var i = 0; i < RedisDetailCache.SCAN_BATCH * 2 + 100; i++) {
      ids.add(unique());
    }
    ids.forEach(cache::find);
    redis.opsForValue().set("rl:bucket-of-another-feature", "1", Duration.ofMinutes(1));

    cache.clear();

    assertThat(ids).noneMatch(id -> redis.hasKey("search:detail:v1:" + id));
    assertThat(redis.hasKey("rl:bucket-of-another-feature")).isTrue();
    redis.delete("rl:bucket-of-another-feature");
  }

  @Test
  void clearingAnEmptyNamespaceDoesNothing() {
    cache.clear();
    cache.clear();

    assertThat(redis.keys("search:detail:v1:*")).isEmpty();
  }
}
