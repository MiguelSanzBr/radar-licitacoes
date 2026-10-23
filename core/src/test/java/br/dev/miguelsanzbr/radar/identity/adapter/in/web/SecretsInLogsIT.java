package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.RedisTestConfig;
import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Credentials that callers send never reach the log (OWASP A09): an API key that is valid, one that
 * is unknown, one that is malformed, and bearer tokens that do not verify. The application's own
 * loggers are at DEBUG here, so a line that echoed a header at any level would show.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {"radar.rate-limit.enabled=true", "logging.level.br.dev.miguelsanzbr.radar=DEBUG"})
@Import(RedisTestConfig.class)
class SecretsInLogsIT extends PostgresIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String UNKNOWN_KEY = "rk_0badc0de_" + "Zq3".repeat(14) + "Zq";
  private static final String MALFORMED_KEY = "malformed-key-secret-4711";
  private static final String BEARER =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJsb2ctc3ViamVjdCJ9.c2lnLXNlY3JldA";

  @DynamicPropertySource
  static void redisTimeouts(DynamicPropertyRegistry registry) {
    // why: the first command opens the connection, which can take longer than the production
    // timeout
    registry.add("spring.data.redis.timeout", () -> "5s");
    registry.add("spring.data.redis.connect-timeout", () -> "5s");
  }

  @Autowired MockMvc mvc;

  @Test
  void keysAndTokensSentByCallersAreNeverLogged(CapturedOutput output) throws Exception {
    var created =
        mvc.perform(post("/api/v1/api-keys").with(Auth.supplier("log-subject")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var validKey = JSON.readTree(created).get("key").asString();

    mvc.perform(get("/api/v1/procurements").header("X-Api-Key", validKey))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/stats").header("X-Api-Key", UNKNOWN_KEY))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/organs").param("q", "prefeitura").header("X-Api-Key", MALFORMED_KEY))
        .andExpect(status().isUnauthorized());
    // well formed, so the decoder asks the identity provider for its keys, which are not there
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + BEARER))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get("/ops/health-panel").header("Authorization", "Bearer " + MALFORMED_KEY))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/procurements").header("Authorization", "Basic bG9nOnNlY3JldA=="))
        .andExpect(status().isOk());

    assertThat(validKey).startsWith("rk_");
    assertThat(output.getAll())
        .doesNotContain(
            validKey,
            validKey.substring(12),
            UNKNOWN_KEY,
            "Zq3Zq3",
            MALFORMED_KEY,
            BEARER,
            "c2lnLXNlY3JldA",
            "bG9nOnNlY3JldA");
  }
}
