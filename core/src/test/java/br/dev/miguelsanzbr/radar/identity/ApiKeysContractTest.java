package br.dev.miguelsanzbr.radar.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The API key routes are not metered, so the contract must not promise rate limit answers. */
class ApiKeysContractTest {

  private static Map<?, ?> responses(String path, String method) throws IOException {
    try (var in = ApiKeysContractTest.class.getResourceAsStream("/openapi/radar-api.v1.yaml")) {
      var spec = (Map<?, ?>) new Yaml().load(in);
      var item = (Map<?, ?>) ((Map<?, ?>) spec.get("paths")).get(path);
      return (Map<?, ?>) ((Map<?, ?>) item.get(method)).get("responses");
    }
  }

  private static List<String> codes(Map<?, ?> responses) {
    return responses.keySet().stream().map(String::valueOf).toList();
  }

  @Test
  void creatingAKeyHasNoRateLimitHeadersAndNo429() throws IOException {
    var responses = responses("/api/v1/api-keys", "post");

    assertThat(codes(responses)).contains("201", "401", "403", "422").doesNotContain("429");
    assertThat(responses.get("201").toString()).doesNotContain("RateLimit");
  }

  @Test
  void revokingAKeyHasNoRateLimitHeadersAndNo429() throws IOException {
    var responses = responses("/api/v1/api-keys/{prefix}", "delete");

    assertThat(codes(responses)).contains("204", "401", "403", "404").doesNotContain("429");
    assertThat(responses.get("204").toString()).doesNotContain("RateLimit");
  }
}
