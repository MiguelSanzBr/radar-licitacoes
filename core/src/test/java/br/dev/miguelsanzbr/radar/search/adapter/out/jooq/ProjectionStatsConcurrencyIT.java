package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.rebucket;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two consumers on the same aggregate or on crossing buckets must neither double count nor hang.
 */
@OutboxSpringBootTest
class ProjectionStatsConcurrencyIT {

  private static final Instant WHEN = Instant.parse("2026-03-15T12:00:00Z");
  private static final int ROUNDS = 40;

  @Autowired ProjectionStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  private ProcurementEvent.Discovered first(int n, String uf) {
    return discovered("first-" + n, rebucket(view(id(n), "object", "10.00", 1), uf, 6, WHEN), 1);
  }

  private ProcurementEvent.Updated second(int n, String uf) {
    return updated(
        "second-" + n,
        rebucket(view(id(n), "object", "10.00", 1, 2, WHEN.plusSeconds(60)), uf, 6, WHEN),
        2);
  }

  /** Runs both tasks together, round after round, and fails on the first exception. */
  private void together(int rounds, Round a, Round b) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (var round = 0; round < rounds; round++) {
        var barrier = new CyclicBarrier(2);
        var current = round;
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (var task : List.of(a, b)) {
          futures.add(
              pool.submit(
                  () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    task.run(current);
                    return null;
                  }));
        }
        for (var future : futures) {
          future.get(60, TimeUnit.SECONDS);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private interface Round {
    void run(int n);
  }

  @Test
  void theFirstInsertAndAnUpdateOfTheSameProcurementCountOnce() throws Exception {
    together(ROUNDS, n -> store.project(first(n, "SP"), n), n -> store.project(second(n, "MG"), n));

    assertThat(
            jdbc.queryForObject("SELECT sum(count) FROM search.procurement_stats_view", Long.class))
        .isEqualTo(ROUNDS);
  }

  @Test
  void procurementsMovingInOppositeDirectionsDoNotDeadlock() throws Exception {
    for (var n = 0; n < ROUNDS; n++) {
      store.project(first(n, "SP"), n);
      store.project(first(1000 + n, "MG"), n);
    }

    together(
        ROUNDS,
        n -> store.project(second(n, "MG"), n),
        n -> store.project(second(1000 + n, "SP"), n));

    assertThat(
            jdbc.queryForObject("SELECT sum(count) FROM search.procurement_stats_view", Long.class))
        .isEqualTo(2L * ROUNDS);
    assertThat(jdbc.queryForList("SELECT uf, count FROM search.procurement_stats_view ORDER BY uf"))
        .extracting(r -> r.get("uf") + "/" + r.get("count"))
        .containsExactly("MG/" + ROUNDS, "SP/" + ROUNDS);
  }
}
