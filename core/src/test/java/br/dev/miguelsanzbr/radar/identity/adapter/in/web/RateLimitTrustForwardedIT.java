package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.RedisTestConfig;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Behind a trusted proxy the client is the address the proxy appended to X-Forwarded-For. */
@TestPropertySource(
    properties = {"radar.rate-limit.enabled=true", "radar.http.trust-forwarded=true"})
@Import(RedisTestConfig.class)
class RateLimitTrustForwardedIT extends PostgresIT {

  @DynamicPropertySource
  static void smallPool(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    // why: the first command opens the connection, which can take longer than the production
    // timeout
    registry.add("spring.data.redis.timeout", () -> "5s");
    registry.add("spring.data.redis.connect-timeout", () -> "5s");
  }

  @Autowired MockMvc mvc;

  @Test
  void eachForwardedClientHasItsOwnBucketAndASpoofedFirstEntryChangesNothing() throws Exception {
    for (var i = 0; i < 30; i++) {
      mvc.perform(get("/api/v1/stats").header("X-Forwarded-For", "10." + i + ".0.1, 192.0.2.10"))
          .andExpect(header().string("RateLimit-Remaining", String.valueOf(29 - i)));
    }

    mvc.perform(get("/api/v1/stats").header("X-Forwarded-For", "10.99.0.1, 192.0.2.10"))
        .andExpect(status().isTooManyRequests());
    mvc.perform(get("/api/v1/stats").header("X-Forwarded-For", "10.0.0.1, 192.0.2.11"))
        .andExpect(header().string("RateLimit-Remaining", "29"));
  }
}
