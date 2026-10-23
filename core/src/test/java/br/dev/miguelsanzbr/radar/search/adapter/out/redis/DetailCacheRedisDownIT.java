package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.SharedRedis;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis stopped for good, with the rate limit off (the client must not depend on it): the detail is
 * still served from Postgres, the projection still commits, failed cache calls are counted, and
 * once the client sees the disconnect every call stays fast.
 */
@TestPropertySource(
    properties = {"radar.search.detail-cache.enabled=true", "radar.rate-limit.enabled=false"})
@DirtiesContext
class DetailCacheRedisDownIT extends PostgresIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(SharedRedis.IMAGE)).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void ownRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @AfterAll
  static void stopRedis() {
    REDIS.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired ProjectionStore store;
  @Autowired MeterRegistry registry;
  @Autowired RedisDetailCache cache;

  private double errors() {
    return registry.get("search_detail_cache_errors_total").counter().count();
  }

  private static long millis(Runnable call) {
    var started = System.nanoTime();
    call.run();
    return Duration.ofNanos(System.nanoTime() - started).toMillis();
  }

  @Test
  void aStoppedRedisStillServesTheDetailFromPostgresAndNeverBlocksTheProjection() throws Exception {
    var id = id(7101);
    var path = "/api/v1/procurements/" + ProcurementIds.toPath(id);
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.procurement_stats_view, search.processed_event,"
                + " search.projection_checkpoint")
        .update();
    store.project(discovered("d-7101", view(id, "Objeto original", "100.00", 1), 1), 0);
    mvc.perform(get(path)).andExpect(status().isOk());
    assertThat(errors()).isZero();
    var before = errors();

    REDIS.stop();

    mvc.perform(get(path))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectDescription").value("Objeto original"));
    store.project(updated("u-7101", view(id, "Objeto corrigido", "100.00", 1, 2, T0), 2), 1);
    mvc.perform(get(path)).andExpect(jsonPath("$.objectDescription").value("Objeto corrigido"));
    assertThat(errors()).isGreaterThan(before);

    // the invalidation is always tried (never skipped), so its latency shows the client settings
    await().atMost(Duration.ofSeconds(15)).until(() -> millis(() -> cache.invalidate(id)) < 50);
    var latencies = new ArrayList<Long>();
    for (var i = 0; i < 20; i++) {
      latencies.add(millis(() -> cache.invalidate(id)));
    }
    Collections.sort(latencies);
    assertThat(latencies.get(18)).as("p95 of %s", latencies).isLessThan(50);
  }
}
