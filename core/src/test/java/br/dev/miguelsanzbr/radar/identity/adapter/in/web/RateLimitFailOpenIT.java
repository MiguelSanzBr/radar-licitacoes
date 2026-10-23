package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import br.dev.miguelsanzbr.radar.SharedRedis;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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
 * Redis of its own (paused here, so the shared one stays untouched): public reads keep answering,
 * unmetered, and the fail-open counter says how many did.
 */
@TestPropertySource(properties = "radar.rate-limit.enabled=true")
@DirtiesContext
class RateLimitFailOpenIT extends PostgresIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(SharedRedis.IMAGE)).withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void ownRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
  }

  @AfterAll
  static void stopRedis() {
    REDIS.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired MeterRegistry registry;

  private double failedOpen() {
    return registry.get("rate_limit_fail_open_total").counter().count();
  }

  @Test
  void aPausedRedisDoesNotTakeTheReadsDown() throws Exception {
    // the first command opens the connection, which may outlast the short production timeout
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> mvc.perform(get("/api/v1/stats")).andExpect(header().exists("RateLimit-Limit")));
    var before = failedOpen();

    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    try {
      for (var i = 0; i < 3; i++) {
        var response = mvc.perform(get("/api/v1/stats")).andReturn().getResponse();
        assertThat(response.getStatus()).isNotEqualTo(429).isNotEqualTo(500);
        assertThat(response.getHeader("RateLimit-Limit")).isNull();
      }
      assertThat(failedOpen()).isEqualTo(before + 3);
    } finally {
      REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> mvc.perform(get("/api/v1/stats")).andExpect(header().exists("RateLimit-Limit")));
  }
}
