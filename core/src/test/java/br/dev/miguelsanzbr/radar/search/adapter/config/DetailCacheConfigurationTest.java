package br.dev.miguelsanzbr.radar.search.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.dev.miguelsanzbr.radar.search.adapter.out.redis.RedisDetailCache;
import br.dev.miguelsanzbr.radar.search.application.DetailCacheInvalidation;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The flag that puts the cache in front of the database, or leaves the database alone. */
class DetailCacheConfigurationTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final DetailReadModel database = id -> Optional.empty();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(DetailCacheConfiguration.class)
          .withBean("jooqDetailReadModel", DetailReadModel.class, () -> database)
          .withBean(StringRedisTemplate.class, () -> redis)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(Clock.class, Clock::systemUTC);

  @Test
  void theCacheIsOnByDefaultAndIsTheReadModelTheUseCaseGets() {
    runner.run(
        context -> {
          assertThat(context.getBean(DetailReadModel.class)).isInstanceOf(RedisDetailCache.class);
          assertThat(context.getBean(DetailCacheInvalidation.class))
              .isSameAs(context.getBean(DetailReadModel.class));
        });
  }

  @Test
  void theTtlIsAnHourUnlessConfigured() {
    runner.run(
        context ->
            assertThat(context.getBean(DetailCacheProperties.class).ttl())
                .isEqualTo(Duration.ofHours(1)));
    runner
        .withPropertyValues("radar.search.detail-cache.ttl=10m")
        .run(
            context ->
                assertThat(context.getBean(DetailCacheProperties.class).ttl())
                    .isEqualTo(Duration.ofMinutes(10)));
  }

  @Test
  void switchedOffTheDatabaseIsReadDirectlyAndRedisIsNotEvenNeeded() {
    // no template, registry or clock bean: the cache would not start without them
    new ApplicationContextRunner()
        .withUserConfiguration(DetailCacheConfiguration.class)
        .withBean("jooqDetailReadModel", DetailReadModel.class, () -> database)
        .withPropertyValues("radar.search.detail-cache.enabled=false")
        .run(
            context -> {
              assertThat(context.getBean(DetailReadModel.class)).isSameAs(database);
              var invalidation = context.getBean(DetailCacheInvalidation.class);

              invalidation.invalidate("id");
              invalidation.clear();
            });
  }
}
