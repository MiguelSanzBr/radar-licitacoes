package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import br.dev.miguelsanzbr.radar.SharedRedis;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis stopped for good (connection refused, not just hung): once the client notices the
 * disconnect it rejects commands at once, so reads fail open without waiting for the timeout.
 */
@TestPropertySource(properties = "radar.rate-limit.enabled=true")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitRedisDownIT extends PostgresIT {

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

  /** Milliseconds one read takes, and whether it was let through unmetered. */
  private record Read(long millis, boolean unmetered) {}

  private Read read() throws Exception {
    var started = System.nanoTime();
    var response = mvc.perform(get("/api/v1/stats")).andReturn().getResponse();
    return new Read(
        Duration.ofNanos(System.nanoTime() - started).toMillis(),
        response.getHeader("RateLimit-Limit") == null);
  }

  @Test
  @Order(2)
  void aStoppedRedisDoesNotMakeEveryReadWaitForTheTimeout() throws Exception {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> mvc.perform(get("/api/v1/stats")).andExpect(header().exists("RateLimit-Limit")));

    REDIS.stop();

    // the first reads may still wait while the client notices the disconnect
    await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () -> {
              var r = read();
              return r.unmetered() && r.millis() < 50;
            });
    var before = failedOpen();
    var millis = new ArrayList<Long>();
    for (var i = 0; i < 20; i++) {
      var r = read();
      assertThat(r.unmetered()).isTrue();
      millis.add(r.millis());
    }

    Collections.sort(millis);
    assertThat(millis.get(18)).as("p95 of %s", millis).isLessThan(50);
    assertThat(failedOpen()).isEqualTo(before + 20);
  }

  // runs before the test that stops the container for good
  @Test
  @Order(1)
  void meteringResumesAfterARealDisconnectWithRedisStillUp() throws Exception {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> mvc.perform(get("/api/v1/stats")).andExpect(header().exists("RateLimit-Limit")));

    // drops every client connection, the app's included; Redis itself keeps running
    REDIS.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "normal");

    await()
        .atMost(Duration.ofSeconds(35))
        .untilAsserted(
            () -> mvc.perform(get("/api/v1/stats")).andExpect(header().exists("RateLimit-Limit")));
  }
}
