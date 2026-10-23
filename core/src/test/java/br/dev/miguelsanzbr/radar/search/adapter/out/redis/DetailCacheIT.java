package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.APPLIED;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.DEFERRED;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.RedisTestConfig;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.application.DetailCacheInvalidation;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The detail route with the cache on, against the real Postgres and Redis: what is cached, when it
 * is dropped, and that the validator still follows the clock.
 */
@TestPropertySource(properties = "radar.search.detail-cache.enabled=true")
@Import({RedisTestConfig.class, DetailCacheIT.Moving.class})
class DetailCacheIT extends PostgresIT {

  private static final String ID = id(7001);
  private static final String PATH = "/api/v1/procurements/" + ProcurementIds.toPath(ID);
  private static final String KEY = "search:detail:v1:" + ID;

  @TestConfiguration(proxyBeanMethods = false)
  static class Moving {
    @Bean
    @Primary
    MutableClock movingClock() {
      return new MutableClock(T0);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @Autowired ProjectionStore store;
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry registry;

  @Autowired
  @Qualifier("searchTransactionManager")
  PlatformTransactionManager transactions;

  @MockitoSpyBean(name = "jooqDetailReadModel")
  DetailReadModel database;

  @MockitoSpyBean DetailCacheInvalidation invalidation;

  @BeforeEach
  void seed() {
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.procurement_stats_view, search.processed_event,"
                + " search.projection_checkpoint")
        .update();
    redis.delete(KEY);
    clock.advance(Duration.between(clock.instant(), T0.plus(Duration.ofHours(2))));
    store.project(discovered("d-7001", view(ID, "Objeto original", "100.00", 1), 1), 0);
    clearInvocations(database, invalidation);
  }

  private double requests(String result) {
    return registry
        .get("search_detail_cache_requests_total")
        .tag("result", result)
        .counter()
        .count();
  }

  @Test
  void theFirstGetIsAMissThatStoresAndTheSecondIsAHitThatSkipsPostgres() throws Exception {
    var hits = requests("hit");
    var misses = requests("miss");

    mvc.perform(get(PATH)).andExpect(status().isOk());
    assertThat(redis.hasKey(KEY)).isTrue();
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectDescription").value("Objeto original"));

    verify(database, times(1)).find(ID);
    assertThat(requests("miss") - misses).isEqualTo(1);
    assertThat(requests("hit") - hits).isEqualTo(1);
  }

  @Test
  void theEtagFollowsThePhaseWhileTheStoredDetailStaysCached() throws Exception {
    var open =
        mvc.perform(get(PATH))
            .andExpect(jsonPath("$.proposalPhase").value("OPEN"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    mvc.perform(get(PATH)).andExpect(status().isOk());

    clock.advance(Duration.ofHours(30));
    var closed =
        mvc.perform(get(PATH))
            .andExpect(jsonPath("$.proposalPhase").value("CLOSED"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    verify(database, times(1)).find(ID);
    assertThat(closed).isNotEqualTo(open);
    mvc.perform(get(PATH).header("If-None-Match", open)).andExpect(status().isOk());
    mvc.perform(get(PATH).header("If-None-Match", closed)).andExpect(status().isNotModified());
  }

  @Test
  void anUpdatedEventDropsTheKeyAfterTheCommitAndTheNextGetSeesTheNewContent() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());

    store.project(updated("u-7001", view(ID, "Objeto corrigido", "100.00", 1, 2, T0), 2), 1);

    assertThat(redis.hasKey(KEY)).isFalse();
    mvc.perform(get(PATH)).andExpect(jsonPath("$.objectDescription").value("Objeto corrigido"));
    verify(database, times(2)).find(ID);
  }

  @Test
  void aSituationChangeDropsTheKey() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());

    store.project(situationChanged("s-7001", ID, 2, 1, 4), 1);

    assertThat(redis.hasKey(KEY)).isFalse();
    mvc.perform(get(PATH)).andExpect(jsonPath("$.status").value("SUSPENDED"));
  }

  @Test
  void aClosingDropsTheKey() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());

    store.project(closed("c-7001", ID, 2), 1);

    assertThat(redis.hasKey(KEY)).isFalse();
  }

  @Test
  void aDiscoveredEventDropsTheKeyToo() throws Exception {
    redis.opsForValue().set(KEY, "stale", Duration.ofMinutes(5));

    store.project(discovered("d-7001-again", view(id(7002), "Outro", "1.00", 1), 1), 1);
    assertThat(redis.hasKey(KEY)).isTrue();

    store.project(discovered("d-7001-redo", view(ID, "Objeto original", "100.00", 1), 1), 2);
    assertThat(redis.hasKey(KEY)).isFalse();
  }

  @Test
  void theKeyGoesOnlyAfterTheCommit() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              store.project(
                  updated("u-7001", view(ID, "Objeto corrigido", "100.00", 1, 2, T0), 2), 1);
              assertThat(redis.hasKey(KEY)).as("still cached before the commit").isTrue();
            });

    assertThat(redis.hasKey(KEY)).isFalse();
  }

  @Test
  void aRolledBackProjectionLeavesTheKeyAlone() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              store.project(
                  updated("u-7001", view(ID, "Objeto corrigido", "100.00", 1, 2, T0), 2), 1);
              tx.setRollbackOnly();
            });

    assertThat(redis.hasKey(KEY)).isTrue();
    mvc.perform(get(PATH)).andExpect(jsonPath("$.objectDescription").value("Objeto original"));
  }

  @Test
  void aBatchDropsEachAppliedIdOnceInOneCallAfterTheBatchCommits() throws Exception {
    var other = id(7002);
    var otherKey = "search:detail:v1:" + other;
    redis.opsForValue().set(otherKey, "stale", Duration.ofMinutes(5));
    mvc.perform(get(PATH)).andExpect(status().isOk());

    var outcomes =
        store.projectAll(
            List.of(
                new OffsetEvent(
                    updated("bu-7001", view(ID, "Objeto corrigido", "100.00", 1, 2, T0), 2), 1),
                new OffsetEvent(
                    updated("bu-7001-b", view(ID, "Objeto de novo", "100.00", 1, 3, T0), 3), 2),
                new OffsetEvent(discovered("bd-7002", view(other, "Outro", "1.00", 1), 1), 3),
                new OffsetEvent(closed("bc-7999", id(7999), 2), 4),
                new OffsetEvent(
                    discovered("d-7001", view(ID, "Objeto original", "100.00", 1), 1), 5)));

    assertThat(outcomes).containsExactly(APPLIED, APPLIED, APPLIED, DEFERRED, DUPLICATE);
    var ids = ArgumentCaptor.<Collection<String>>captor();
    verify(invalidation, times(1)).invalidateAll(ids.capture());
    assertThat(ids.getValue()).containsExactlyInAnyOrder(ID, other);
    verify(invalidation, never()).invalidate(anyString());
    assertThat(redis.hasKey(KEY)).isFalse();
    assertThat(redis.hasKey(otherKey)).isFalse();
  }

  @Test
  void aRolledBackBatchDropsNothing() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              store.projectAll(
                  List.of(
                      new OffsetEvent(
                          updated("bu-7001", view(ID, "Objeto corrigido", "100.00", 1, 2, T0), 2),
                          1)));
              tx.setRollbackOnly();
            });

    assertThat(redis.hasKey(KEY)).isTrue();
    verify(invalidation, never()).invalidateAll(anyCollection());
  }

  @Test
  void aReplayThatEmptiesTheReadModelEmptiesTheCacheToo() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk());
    redis.opsForValue().set("rl:other-feature", "1", Duration.ofMinutes(1));

    store.reset();

    assertThat(redis.hasKey(KEY)).isFalse();
    assertThat(redis.hasKey("rl:other-feature")).isTrue();
    redis.delete("rl:other-feature");
    mvc.perform(get(PATH)).andExpect(status().isNotFound());
  }
}
