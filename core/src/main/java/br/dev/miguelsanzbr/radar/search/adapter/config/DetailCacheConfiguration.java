package br.dev.miguelsanzbr.radar.search.adapter.config;

import br.dev.miguelsanzbr.radar.search.adapter.out.redis.RedisDetailCache;
import br.dev.miguelsanzbr.radar.search.application.DetailCacheInvalidation;
import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The cache of the detail (ADR-0027). On, it is the primary {@link DetailReadModel} in front of the
 * jOOQ one and the target of the projector's invalidation; off ({@code
 * radar.search.detail-cache.enabled=false}), the database is read directly and nothing touches
 * Redis.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DetailCacheProperties.class)
class DetailCacheConfiguration {

  private static final String ENABLED = "radar.search.detail-cache.enabled";

  @Bean
  @Primary
  @ConditionalOnProperty(name = ENABLED, havingValue = "true", matchIfMissing = true)
  RedisDetailCache redisDetailCache(
      @Qualifier("jooqDetailReadModel") DetailReadModel database,
      StringRedisTemplate redis,
      DetailCacheProperties properties,
      MeterRegistry registry,
      Clock clock) {
    return new RedisDetailCache(database, redis, properties.ttl(), registry, clock);
  }

  /** Declared after the cache: it exists only when the cache does not. */
  @Bean
  @ConditionalOnMissingBean(DetailCacheInvalidation.class)
  DetailCacheInvalidation noDetailCache() {
    return new DetailCacheInvalidation() {
      @Override
      public void invalidateAll(Collection<String> ids) {}

      @Override
      public void clear() {}
    };
  }
}
