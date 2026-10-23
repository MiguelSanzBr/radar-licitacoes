package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import br.dev.miguelsanzbr.radar.search.application.DetailCacheInvalidation;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside in front of the stored detail (ADR-0027). It holds what the read model returns, the
 * detail and its stored etag, never the phase or the etag the controller derives from the clock. A
 * procurement that is not projected is not cached. Redis is an optimisation, never a dependency:
 * any failure reads the database (fail-open), is counted in {@code
 * search_detail_cache_errors_total} and logged as a WARN at most once a minute.
 */
public final class RedisDetailCache implements DetailReadModel, DetailCacheInvalidation {

  /**
   * The version is part of the key: bump it on ANY change to {@code ProcurementDetail}, additions
   * too. During a rolling deploy old and new instances share this Redis, and a value written in the
   * other shape would read as a silently different detail (a missing field becomes null or zero).
   */
  static final String PREFIX = "search:detail:v1:";

  /** Keys per UNLINK when the namespace is wiped. */
  static final int SCAN_BATCH = 500;

  private static final Logger LOG = LoggerFactory.getLogger(RedisDetailCache.class);
  private static final long WARN_EVERY_SECONDS = 60;

  /** After a Redis failure, reads and stores skip Redis for this long (no timeout per request). */
  private static final Duration BYPASS = Duration.ofSeconds(10);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DetailReadModel delegate;
  private final StringRedisTemplate redis;
  private final Duration ttl;
  private final Counter hits;
  private final Counter misses;
  private final Counter errors;
  private final Clock clock;
  private final AtomicReference<Instant> lastWarning = new AtomicReference<>();
  private final AtomicReference<Instant> bypassUntil = new AtomicReference<>(Instant.MIN);

  public RedisDetailCache(
      DetailReadModel delegate,
      StringRedisTemplate redis,
      Duration ttl,
      MeterRegistry registry,
      Clock clock) {
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive: " + ttl);
    }
    this.delegate = delegate;
    this.redis = redis;
    this.ttl = ttl;
    this.hits = requests(registry, "hit");
    this.misses = requests(registry, "miss");
    this.errors =
        Counter.builder("search_detail_cache_errors_total")
            .description("Detail cache operations that failed and fell back to the database")
            .register(registry);
    this.clock = clock;
  }

  private static Counter requests(MeterRegistry registry, String result) {
    return Counter.builder("search_detail_cache_requests_total")
        .description("Detail reads by cache result; hit ratio is hit / (hit + miss)")
        .tag("result", result)
        .register(registry);
  }

  @Override
  public Optional<ProcurementDetail> find(String id) {
    if (clock.instant().isBefore(bypassUntil.get())) {
      return delegate.find(id);
    }
    var key = PREFIX + id;
    String cached;
    try {
      cached = redis.opsForValue().get(key);
    } catch (RuntimeException e) {
      unavailable(e);
      return delegate.find(id);
    }
    if (cached != null) {
      try {
        var detail = JSON.readValue(cached, ProcurementDetail.class);
        hits.increment();
        return Optional.of(detail);
      } catch (JacksonException e) {
        // why: a value of another shape (an old deploy) is replaced by the row below
        failed(e);
      }
    }
    misses.increment();
    var found = delegate.find(id);
    found.ifPresent(detail -> store(key, JSON.writeValueAsString(detail)));
    return found;
  }

  private void store(String key, String json) {
    try {
      redis.opsForValue().set(key, json, ttl);
    } catch (RuntimeException e) {
      unavailable(e);
    }
  }

  @Override
  public void invalidateAll(Collection<String> ids) {
    try {
      redis.delete(ids.stream().map(id -> PREFIX + id).toList());
    } catch (RuntimeException e) {
      unavailable(e);
    }
  }

  /** SCAN and UNLINK in batches: KEYS would block Redis, and one huge UNLINK would too. */
  @Override
  public void clear() {
    var options = ScanOptions.scanOptions().match(PREFIX + "*").count(SCAN_BATCH).build();
    try (var keys = redis.scan(options)) {
      var batch = new ArrayList<String>(SCAN_BATCH);
      while (keys.hasNext()) {
        batch.add(keys.next());
        if (batch.size() == SCAN_BATCH) {
          redis.unlink(batch);
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        redis.unlink(batch);
      }
    } catch (RuntimeException e) {
      unavailable(e);
    }
  }

  private void unavailable(RuntimeException cause) {
    bypassUntil.set(clock.instant().plus(BYPASS));
    failed(cause);
  }

  private void failed(RuntimeException cause) {
    errors.increment();
    var now = clock.instant();
    var previous = lastWarning.getAndUpdate(last -> due(last, now) ? now : last);
    if (due(previous, now)) {
      // the text, not the Throwable: a Throwable argument would be logged as a stack trace
      LOG.warn(
          "Detail cache unavailable, reading from the database (fail-open): {}", cause.toString());
    }
  }

  private static boolean due(Instant last, Instant now) {
    return last == null || !now.isBefore(last.plusSeconds(WARN_EVERY_SECONDS));
  }
}
