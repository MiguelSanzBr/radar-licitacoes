package br.dev.miguelsanzbr.radar;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Points Redis at the shared container. Like {@link PostgresTestConfig}, it is deliberately not a
 * container bean: Boot would stop it when the context closes and it outlives every context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfig {

  @Bean
  DataRedisConnectionDetails sharedRedisConnectionDetails() {
    var redis = SharedRedis.INSTANCE;
    return new DataRedisConnectionDetails() {
      @Override
      public Standalone getStandalone() {
        return Standalone.of(redis.getHost(), redis.getMappedPort(6379));
      }
    };
  }
}
