package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** The cache-aside of the stored detail, against an in-memory stand-in for Redis. */
class RedisDetailCacheTest {

  private static final Duration TTL = Duration.ofHours(1);

  private final Map<String, String> redisValues = new HashMap<>();
  private final AtomicInteger databaseReads = new AtomicInteger();
  private final Map<String, ProcurementDetail> database = new HashMap<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);

  private final ValueOperations<String, String> values = redis.opsForValue();

  private final DetailReadModel delegate =
      id -> {
        databaseReads.incrementAndGet();
        return Optional.ofNullable(database.get(id));
      };

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));

  private final RedisDetailCache cache =
      new RedisDetailCache(delegate, redis, TTL, registry, clock);

  RedisDetailCacheTest() {
    when(values.get(anyString())).thenAnswer(call -> redisValues.get(call.<String>getArgument(0)));
    doAnswer(
            call -> {
              redisValues.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
  }

  private double requests(String result) {
    return registry
        .get("search_detail_cache_requests_total")
        .tag("result", result)
        .counter()
        .count();
  }

  @Test
  void aMissReadsTheDatabaseAndStoresTheDetailUnderTheVersionedKeyWithTheTtl() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));

    var found = cache.find(Details.ID);

    assertThat(found).contains(Details.full(Details.ID, "etag-1"));
    verify(values).set(eq("search:detail:v1:" + Details.ID), anyString(), eq(TTL));
    assertThat(requests("miss")).isEqualTo(1);
    assertThat(requests("hit")).isZero();
  }

  @Test
  void aHitIsServedFromRedisWithoutReadingTheDatabase() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    cache.find(Details.ID);

    var again = cache.find(Details.ID);

    assertThat(again).contains(Details.full(Details.ID, "etag-1"));
    assertThat(databaseReads).hasValue(1);
    assertThat(requests("hit")).isEqualTo(1);
    assertThat(requests("miss")).isEqualTo(1);
  }

  @Test
  void theStoredEtagSurvivesTheRoundTripAndEveryOptionalFieldMayBeNull() {
    database.put(Details.ID, Details.sparse(Details.ID, "etag-sparse"));
    cache.find(Details.ID);

    var cached = cache.find(Details.ID);

    assertThat(cached).contains(Details.sparse(Details.ID, "etag-sparse"));
    assertThat(databaseReads).hasValue(1);
  }

  @Test
  void aProcurementThatIsNotProjectedIsNotCached() {
    assertThat(cache.find(Details.ID)).isEmpty();
    assertThat(cache.find(Details.ID)).isEmpty();

    assertThat(databaseReads).hasValue(2);
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    assertThat(requests("miss")).isEqualTo(2);
  }

  private static final RedisConnectionFailureException DOWN =
      new RedisConnectionFailureException("connection refused");

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(RedisDetailCache.class);

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private double errors() {
    return registry.get("search_detail_cache_errors_total").counter().count();
  }

  @Test
  void theErrorCounterExistsFromTheStart() {
    assertThat(errors()).isZero();
  }

  @Test
  void aRedisThatCannotBeReadFailsOpenToTheDatabaseWithoutTryingToStore() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    when(values.get(anyString())).thenThrow(DOWN);

    var found = cache.find(Details.ID);

    assertThat(found).contains(Details.full(Details.ID, "etag-1"));
    assertThat(errors()).isEqualTo(1);
    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    assertThat(registry.find("search_detail_cache_requests_total").counters())
        .allSatisfy(counter -> assertThat(counter.count()).isZero());
  }

  @Test
  void aRedisThatCannotStoreStillAnswersFromTheDatabase() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    doThrow(DOWN).when(values).set(anyString(), anyString(), any(Duration.class));

    var found = cache.find(Details.ID);

    assertThat(found).contains(Details.full(Details.ID, "etag-1"));
    assertThat(errors()).isEqualTo(1);
    assertThat(requests("miss")).isEqualTo(1);
  }

  @Test
  void aValueThatDoesNotParseIsReplacedByTheDatabaseRow() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    redisValues.put("search:detail:v1:" + Details.ID, "{not json");

    var found = cache.find(Details.ID);

    assertThat(found).contains(Details.full(Details.ID, "etag-1"));
    assertThat(errors()).isEqualTo(1);
    assertThat(requests("miss")).isEqualTo(1);
    assertThat(cache.find(Details.ID)).contains(Details.full(Details.ID, "etag-1"));
    assertThat(databaseReads).hasValue(1);
  }

  @Test
  void theWarningIsLoggedOnceAMinute() {
    when(redis.delete(ArgumentMatchers.<Collection<String>>any())).thenThrow(DOWN);

    cache.invalidate(Details.ID);
    clock.advance(Duration.ofSeconds(59));
    cache.invalidate(Details.ID);
    cache.invalidate(Details.ID);

    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(logs.list.getFirst().getFormattedMessage()).contains("connection refused");

    clock.advance(Duration.ofSeconds(1));
    cache.invalidate(Details.ID);

    assertThat(logs.list).hasSize(2);
    assertThat(errors()).isEqualTo(4);
  }

  @Test
  void aKeyThatCannotBeDeletedIsCountedAndNeverThrown() {
    when(redis.delete(ArgumentMatchers.<Collection<String>>any())).thenThrow(DOWN);

    cache.invalidate(Details.ID);

    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void aNamespaceThatCannotBeScannedIsCountedAndNeverThrown() {
    when(redis.scan(any(ScanOptions.class))).thenThrow(DOWN);

    cache.clear();

    assertThat(errors()).isEqualTo(1);
  }

  private static final IllegalStateException BROKEN =
      new IllegalStateException("factory destroyed");

  @Test
  void anyFailureOfTheClientIsContainedNotJustDataAccessErrors() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    when(values.get(anyString())).thenThrow(BROKEN);

    assertThat(cache.find(Details.ID)).contains(Details.full(Details.ID, "etag-1"));
    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void aStoreThatFailsInAnyWayStillAnswers() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    doThrow(BROKEN).when(values).set(anyString(), anyString(), any(Duration.class));

    assertThat(cache.find(Details.ID)).contains(Details.full(Details.ID, "etag-1"));
    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void aDeleteThatFailsInAnyWayIsCountedNotThrown() {
    when(redis.delete(ArgumentMatchers.<Collection<String>>any())).thenThrow(BROKEN);

    cache.invalidate(Details.ID);

    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void aBatchDeleteThatFailsIsCountedNotThrown() {
    when(redis.delete(ArgumentMatchers.<Collection<String>>any())).thenThrow(BROKEN);

    cache.invalidateAll(List.of(Details.ID, "other"));

    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void aBatchIsOneMultiKeyDelete() {
    cache.invalidateAll(List.of(Details.ID, "other"));

    verify(redis).delete(List.of("search:detail:v1:" + Details.ID, "search:detail:v1:other"));
  }

  @Test
  void aCursorThatCannotBeClosedIsCountedNotThrown() {
    Cursor<String> cursor = mock();
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
    doThrow(BROKEN).when(cursor).close();

    cache.clear();

    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void aReadFailureBypassesRedisForTenSecondsAndThenTriesAgain() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    when(values.get(anyString())).thenThrow(DOWN);

    cache.find(Details.ID);
    clock.advance(Duration.ofSeconds(9));
    cache.find(Details.ID);

    verify(values, times(1)).get(anyString());
    assertThat(errors()).isEqualTo(1);
    assertThat(databaseReads).hasValue(2);
    assertThat(registry.find("search_detail_cache_requests_total").counters())
        .allSatisfy(counter -> assertThat(counter.count()).isZero());

    clock.advance(Duration.ofSeconds(1));
    cache.find(Details.ID);

    verify(values, times(2)).get(anyString());
  }

  @Test
  void aStoreFailureOpensTheWindowToo() {
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));
    doThrow(DOWN).when(values).set(anyString(), anyString(), any(Duration.class));

    cache.find(Details.ID);
    cache.find(Details.ID);

    verify(values, times(1)).get(anyString());
    assertThat(errors()).isEqualTo(1);
  }

  @Test
  void invalidationsAreNeverSkippedEvenWhileTheWindowIsOpen() {
    when(redis.delete(ArgumentMatchers.<Collection<String>>any())).thenThrow(DOWN);

    cache.invalidate(Details.ID);
    cache.find(Details.ID);
    cache.invalidate(Details.ID);

    verify(redis, times(2)).delete(ArgumentMatchers.<Collection<String>>any());
    verify(values, never()).get(anyString());
    assertThat(errors()).isEqualTo(2);
  }

  @Test
  void aValueThatDoesNotParseIsNotAnOutageAndOpensNoWindow() {
    redisValues.put("search:detail:v1:" + Details.ID, "{not json");
    database.put(Details.ID, Details.full(Details.ID, "etag-1"));

    cache.find(Details.ID);
    cache.find(Details.ID);

    verify(values, times(2)).get(anyString());
  }

  @Test
  void theTtlMustBePositive() {
    assertThatThrownBy(() -> new RedisDetailCache(delegate, redis, Duration.ZERO, registry, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
    assertThatThrownBy(
            () -> new RedisDetailCache(delegate, redis, Duration.ofSeconds(-1), registry, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
