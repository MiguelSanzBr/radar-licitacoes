package br.dev.miguelsanzbr.radar.identity.adapter.out.redis;

import br.dev.miguelsanzbr.radar.SharedRedis;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A template on the shared Redis for tests that build the adapter without a Spring context. */
final class SharedRedisTemplate {

  private SharedRedisTemplate() {}

  static StringRedisTemplate open() {
    var factory =
        new LettuceConnectionFactory(
            SharedRedis.INSTANCE.getHost(), SharedRedis.INSTANCE.getMappedPort(6379));
    factory.afterPropertiesSet();
    var template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  static void close(StringRedisTemplate template) {
    ((LettuceConnectionFactory) template.getConnectionFactory()).destroy();
  }
}
