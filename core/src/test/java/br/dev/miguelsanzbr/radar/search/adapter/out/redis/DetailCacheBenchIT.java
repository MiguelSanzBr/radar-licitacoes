package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.RedisTestConfig;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Detail reads with and without the cache (ADR-0027, docs/benchmarks/redis/hit-ratio.md): 2 000
 * stored details, 20 000 reads of a Zipf-like id sequence (the same sequence in both modes),
 * straight through the use case. The cache starts cold, so the fill is part of the measurement. The
 * table lands in {@code core/target/detail-cache-bench.md}.
 *
 * <p>./mvnw -Pbench verify -pl core -am -Dtest=NoSuch -Dit.test=DetailCacheBenchIT
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@Tag("bench")
@TestPropertySource(properties = "radar.search.detail-cache.enabled=true")
@Import(RedisTestConfig.class)
class DetailCacheBenchIT extends PostgresIT {

  private static final int DETAILS = 2_000;
  private static final int READS = 20_000;
  private static final int WARM_UP = 2_000;
  private static final double ZIPF_EXPONENT = 1.0;

  @Autowired ProjectionStore store;
  @Autowired RedisDetailCache cache;
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry registry;

  @Autowired
  @Qualifier("jooqDetailReadModel")
  DetailReadModel database;

  /** Latency of each read, in microseconds, and how many reads it was. */
  private record Run(double p50, double p95, double p99, double mean, double seconds) {

    static Run of(long[] nanos, long total) {
      var sorted = nanos.clone();
      Arrays.sort(sorted);
      return new Run(
          at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99), mean(sorted), total / 1e9);
    }

    private static double at(long[] sorted, double quantile) {
      return sorted[(int) Math.ceil(quantile * sorted.length) - 1] / 1_000.0;
    }

    private static double mean(long[] sorted) {
      return Arrays.stream(sorted).average().orElseThrow() / 1_000.0;
    }
  }

  /** Rank 1 is the most read; the probability of rank k is proportional to 1 / k^s. */
  private static int[] zipf(int reads, long seed) {
    var cumulative = new double[DETAILS];
    var sum = 0.0;
    for (var k = 1; k <= DETAILS; k++) {
      sum += 1 / Math.pow(k, ZIPF_EXPONENT);
      cumulative[k - 1] = sum;
    }
    var random = new Random(seed);
    var ranks = new int[reads];
    for (var i = 0; i < reads; i++) {
      var index = Arrays.binarySearch(cumulative, random.nextDouble() * sum);
      ranks[i] = index >= 0 ? index : -index - 1;
    }
    return ranks;
  }

  private static Run measure(DetailUseCase useCase, int[] ranks) {
    var nanos = new long[ranks.length];
    var started = System.nanoTime();
    for (var i = 0; i < ranks.length; i++) {
      var begin = System.nanoTime();
      useCase.find(id(ranks[i] + 1));
      nanos[i] = System.nanoTime() - begin;
    }
    return Run.of(nanos, System.nanoTime() - started);
  }

  private long usedMemory() {
    return Long.parseLong(
        redis
            .execute(
                (RedisCallback<Properties>)
                    connection -> connection.serverCommands().info("memory"))
            .getProperty("used_memory"));
  }

  private double requests(String result) {
    return registry
        .get("search_detail_cache_requests_total")
        .tag("result", result)
        .counter()
        .count();
  }

  @Test
  void hitRatioAndLatencyWithAndWithoutTheCache() throws Exception {
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.procurement_stats_view, search.processed_event,"
                + " search.projection_checkpoint")
        .update();
    for (var n = 1; n <= DETAILS; n++) {
      store.project(
          discovered("bench-" + n, view(id(n), "Objeto de teste " + n, "100.00", 1), 1), n);
    }
    var direct = new DetailUseCase(database);
    var cached = new DetailUseCase(cache);
    var ranks = zipf(READS, 42);

    measure(direct, zipf(WARM_UP, 7));
    measure(cached, zipf(WARM_UP, 7));
    cache.clear();
    var usedBefore = usedMemory();
    var hits = requests("hit");
    var misses = requests("miss");

    var withoutCache = measure(direct, ranks);
    var withCache = measure(cached, ranks);

    var hit = requests("hit") - hits;
    var miss = requests("miss") - misses;
    var keys = redis.keys(RedisDetailCache.PREFIX + "*");
    var usedAfter = usedMemory();
    var valueBytes =
        keys.stream()
            .mapToLong(key -> redis.opsForValue().get(key).getBytes(StandardCharsets.UTF_8).length)
            .sum();
    var report =
        """
        | modo | p50 (us) | p95 (us) | p99 (us) | media (us) | tempo total (s) |
        |---|---|---|---|---|---|
        | sem cache | %.0f | %.0f | %.0f | %.0f | %.2f |
        | com cache | %.0f | %.0f | %.0f | %.0f | %.2f |

        leituras: %d, detalhes: %d, acertos: %.0f, faltas: %.0f, taxa de acerto: %.4f
        chaves no Redis: %d, bytes de valor por chave (media): %d, memoria do Redis por chave (used_memory, media): %d
        """
            .formatted(
                withoutCache.p50(),
                withoutCache.p95(),
                withoutCache.p99(),
                withoutCache.mean(),
                withoutCache.seconds(),
                withCache.p50(),
                withCache.p95(),
                withCache.p99(),
                withCache.mean(),
                withCache.seconds(),
                READS,
                DETAILS,
                hit,
                miss,
                hit / (hit + miss),
                keys.size(),
                valueBytes / keys.size(),
                (usedAfter - usedBefore) / keys.size());
    Files.writeString(Path.of("target", "detail-cache-bench.md"), report);
    System.out.println(report);

    assertThat(hit + miss).isEqualTo(READS);
    assertThat(keys).hasSize((int) miss);
    assertThat(hit / (hit + miss)).isGreaterThan(0.5);
  }
}
