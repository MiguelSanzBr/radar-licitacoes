package br.dev.miguelsanzbr.radar.identity.adapter.config;

import br.dev.miguelsanzbr.radar.identity.adapter.in.web.RateLimitFilter;
import br.dev.miguelsanzbr.radar.identity.adapter.out.metrics.MicrometerRateLimitMetrics;
import br.dev.miguelsanzbr.radar.identity.adapter.out.redis.RedisRateLimiter;
import br.dev.miguelsanzbr.radar.identity.application.RateLimitUseCase;
import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimitMetrics;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimiter;
import br.dev.miguelsanzbr.radar.identity.domain.RateLimits;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The rate limit of the public reads (ADR-0027); {@code radar.rate-limit.enabled=false} removes it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "radar.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true)
class RateLimitConfiguration {

  @Bean
  RateLimits rateLimits(
      @Value("${radar.rate-limit.free-per-minute:60}") int free,
      @Value("${radar.rate-limit.pro-per-minute:600}") int pro,
      @Value("${radar.rate-limit.anonymous-per-minute:30}") int anonymous) {
    return new RateLimits(free, pro, anonymous);
  }

  @Bean
  RateLimiter rateLimiter(StringRedisTemplate redis) {
    return new RedisRateLimiter(redis);
  }

  /**
   * Redis down must not make every read wait for the command timeout: once the client sees the
   * disconnect it rejects commands at once (the limiter then fails open) and reconnects in the
   * background. The queue bound keeps a hung (not refused) Redis from piling up requests.
   */
  @Bean
  LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
    return options ->
        options.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS).requestQueueSize(1024);
  }

  @Bean
  RateLimitMetrics rateLimitMetrics(MeterRegistry registry, Clock clock) {
    return new MicrometerRateLimitMetrics(registry, clock);
  }

  @Bean
  RateLimitUseCase rateLimitUseCase(
      ApiKeys keys, RateLimiter limiter, RateLimits limits, RateLimitMetrics metrics) {
    return new RateLimitUseCase(keys, limiter, limits, metrics);
  }

  /** After the security chain (order -100): only requests it let through are metered. */
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  RateLimitFilter rateLimitFilter(
      RateLimitUseCase rateLimit, @Value("${radar.http.trust-forwarded:false}") boolean trusting) {
    return new RateLimitFilter(rateLimit, trusting);
  }
}
