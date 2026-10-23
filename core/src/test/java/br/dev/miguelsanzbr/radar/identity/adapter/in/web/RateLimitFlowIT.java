package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.RedisTestConfig;
import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.identity.adapter.out.redis.RedisRateLimiter;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** Real Redis, real keys in Postgres, time in the test's hands: the whole rate limit path. */
@TestPropertySource(properties = "radar.rate-limit.enabled=true")
@Import({RedisTestConfig.class, RateLimitFlowIT.ClockConfig.class})
class RateLimitFlowIT extends PostgresIT {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
    }

    /** The production limiter takes its time from Redis; this one lets the test move it. */
    @Bean
    @Primary
    RateLimiter testLimiter(StringRedisTemplate redis, MutableClock clock) {
      return new RedisRateLimiter(redis, clock);
    }
  }

  @DynamicPropertySource
  static void smallPool(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    // why: the first command opens the connection, which can take longer than the production
    // timeout
    registry.add("spring.data.redis.timeout", () -> "5s");
    registry.add("spring.data.redis.connect-timeout", () -> "5s");
  }

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;

  /** Each test gets addresses of its own, so the buckets left in Redis by others do not matter. */
  private static int counter;

  private String address;

  @BeforeEach
  void freshAddress() {
    address = "198.51.100." + (++counter % 250);
    clock.advance(Duration.ofHours(1));
  }

  private RequestPostProcessor from(String remote) {
    return request -> {
      request.setRemoteAddr(remote);
      return request;
    };
  }

  private ResultActions read(String remote, String apiKey) throws Exception {
    var request = get("/api/v1/procurements").with(from(remote));
    if (apiKey != null) {
      request.header("X-Api-Key", apiKey);
    }
    return mvc.perform(request);
  }

  private String createKey(String subject) throws Exception {
    var body =
        mvc.perform(post("/api/v1/api-keys").with(Auth.supplier(subject)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("key").asString();
  }

  @Test
  void anonymousCallersGetThirtyReadsAPerMinuteThenA429() throws Exception {
    for (var i = 0; i < 30; i++) {
      read(address, null)
          .andExpect(header().string("RateLimit-Limit", "30"))
          .andExpect(header().string("RateLimit-Remaining", String.valueOf(29 - i)))
          .andExpect(header().exists("RateLimit-Reset"));
    }

    read(address, null)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(header().string("RateLimit-Limit", "30"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().string("RateLimit-Reset", "60"))
        .andExpect(jsonPath("$.type").value(DOCS + "rate-limited.md"))
        .andExpect(conforms());

    clock.advance(Duration.ofSeconds(2));
    assertThat(read(address, null).andReturn().getResponse().getStatus()).isNotEqualTo(429);
  }

  @Test
  void twoClientsDoNotShareABucket() throws Exception {
    var other = "203.0.113." + (counter % 250);
    for (var i = 0; i < 30; i++) {
      read(address, null);
    }

    read(address, null).andExpect(status().isTooManyRequests());
    read(other, null).andExpect(header().string("RateLimit-Remaining", "29"));
  }

  @Test
  void forwardedForCannotBeUsedToEscapeTheLimitUnlessTheProxyIsTrusted() throws Exception {
    for (var i = 0; i < 30; i++) {
      mvc.perform(
          get("/api/v1/procurements").with(from(address)).header("X-Forwarded-For", "10.0." + i));
    }

    mvc.perform(
            get("/api/v1/procurements").with(from(address)).header("X-Forwarded-For", "10.9.9.9"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void aFreeKeyGetsSixtyReadsAPerMinuteIndependentOfTheAddress() throws Exception {
    var key = createKey("kc-1");

    for (var i = 0; i < 60; i++) {
      read(address, key)
          .andExpect(header().string("RateLimit-Limit", "60"))
          .andExpect(header().string("RateLimit-Remaining", String.valueOf(59 - i)));
    }

    read(address, key)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "1"));
    // the same address without the key still has its own anonymous bucket
    read(address, null).andExpect(header().string("RateLimit-Remaining", "29"));
    // and the key keeps its bucket when it comes from another address
    read("203.0.113.250", key).andExpect(status().isTooManyRequests());
  }

  @Test
  void aProKeyGetsSixHundredReadsAMinute() throws Exception {
    var key = createKey("kc-1");
    jdbc.sql("UPDATE identity.api_key SET tier = 'pro'").update();

    read(address, key).andExpect(header().string("RateLimit-Limit", "600"));
  }

  @Test
  void aRevokedKeyStopsWorking() throws Exception {
    var key = createKey("kc-1");
    read(address, key).andExpect(header().string("RateLimit-Limit", "60"));

    mvc.perform(delete("/api/v1/api-keys/" + key.substring(3, 11)).with(Auth.supplier("kc-1")))
        .andExpect(status().isNoContent());

    read(address, key)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value(DOCS + "unauthorized.md"))
        .andExpect(conforms());
  }

  @Test
  void anUnknownOrEmptyKeyIsA401() throws Exception {
    read(address, "rk_00000000_nope").andExpect(status().isUnauthorized());
    read(address, "").andExpect(status().isUnauthorized());
  }

  @Test
  void anApiKeyNeverAuthenticatesASupplierOrOperatorRoute() throws Exception {
    var key = createKey("kc-1");

    for (var path :
        new String[] {
          "/api/v1/saved-searches/0198a3a0-0000-7000-8000-000000000002",
          "/api/v1/me",
          "/ops/health-panel"
        }) {
      mvc.perform(get(path).header("X-Api-Key", key))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.type").value(DOCS + "unauthorized.md"));
    }
    mvc.perform(post("/api/v1/api-keys").header("X-Api-Key", key))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/v1/api-keys/" + key.substring(3, 11)).header("X-Api-Key", key))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void routesOutsideThePublicReadsAreNotMetered() throws Exception {
    mvc.perform(get("/actuator/health").with(from(address)))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("RateLimit-Limit"));
  }

  @Test
  void invalidKeysSpendTheAddressBucketSoGuessingIsRateLimitedToo() throws Exception {
    var unknown = "rk_00000000_" + "A".repeat(43);

    for (var i = 0; i < 30; i++) {
      read(address, unknown)
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("WWW-Authenticate", "ApiKey"));
    }

    read(address, unknown).andExpect(status().isTooManyRequests());
    read(address, null).andExpect(status().isTooManyRequests());
  }

  @Test
  void aMalformedKeyIsA401EvenBeforeTheDatabaseIsAsked() throws Exception {
    read(address, "rk_00000000_nope")
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "ApiKey"));
  }

  @Test
  void clientsOfOneV6SlashSixtyFourShareABucket() throws Exception {
    var network = "2001:db8:" + Integer.toHexString(0x1000 + counter) + ":1:";
    for (var i = 0; i < 30; i++) {
      read(network + ":" + Integer.toHexString(i + 1), null);
    }

    read(network + "ffff:ffff:ffff:ffff", null).andExpect(status().isTooManyRequests());
    read("2001:db8:" + Integer.toHexString(0x1000 + counter) + ":2::1", null)
        .andExpect(header().string("RateLimit-Remaining", "29"));
  }
}
