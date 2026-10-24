package br.dev.miguelsanzbr.radar;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Redis client shared by the rate limit and the detail cache (ADR-0027). */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfiguration {

  /**
   * Redis down must not make every read wait for the command timeout: once the client sees the
   * disconnect it rejects commands at once (the limiter and the cache then fail open) and
   * reconnects in the background. The queue bound keeps a hung (not refused) Redis from piling up
   * requests. It does not depend on any feature flag: whoever uses Redis gets it.
   */
  @Bean
  LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
    return options ->
        options.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS).requestQueueSize(1024);
  }
}
