package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Every Redis user (rate limit, detail cache) shares one client that fails fast when Redis is down.
 */
class RedisClientConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(RedisClientConfiguration.class);

  private void assertFailsFast(LettuceClientOptionsBuilderCustomizer customizer) {
    var builder = ClientOptions.builder();
    customizer.customize(builder);
    var options = builder.build();

    assertThat(options.getDisconnectedBehavior()).isEqualTo(DisconnectedBehavior.REJECT_COMMANDS);
    assertThat(options.getRequestQueueSize()).isEqualTo(1024);
  }

  @Test
  void rejectsCommandsWhileDisconnectedAndBoundsTheQueue() {
    runner.run(
        context -> assertFailsFast(context.getBean(LettuceClientOptionsBuilderCustomizer.class)));
  }

  @Test
  void doesNotDependOnTheRateLimitFlag() {
    runner
        .withPropertyValues(
            "radar.rate-limit.enabled=false", "radar.search.detail-cache.enabled=false")
        .run(
            context ->
                assertFailsFast(context.getBean(LettuceClientOptionsBuilderCustomizer.class)));
  }
}
