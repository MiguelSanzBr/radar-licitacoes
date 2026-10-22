package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.outbox.EventPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LivenessProbeTest {

  @LocalServerPort int port;

  @Autowired Environment environment;

  // the relay needs a publisher, and this context has no broker
  @TestConfiguration
  static class Publisher {
    @Bean
    EventPublisher eventPublisher() {
      return message -> {};
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    var postgres = SharedPostgres.INSTANCE;
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("radar.outbox.relay-enabled", () -> "false");
    registry.add("radar.outbox.publisher", () -> "inmemory");
  }

  @MockitoBean JwtDecoder decoder;

  private HttpResponse<String> get(String path) throws Exception {
    return get(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)));
  }

  private HttpResponse<String> get(HttpRequest.Builder request) throws Exception {
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getAsOperator(String path) throws Exception {
    when(decoder.decode("operator"))
        .thenReturn(
            Jwt.withTokenValue("operator")
                .header("alg", "RS256")
                .subject("ops")
                .claim("realm_access", Map.of("roles", List.of("operator")))
                .build());
    return get(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer operator"));
  }

  @Test
  void livenessProbeReportsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health/liveness");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void readinessProbeReportsUp() throws Exception {
    assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
  }

  @Test
  void otherActuatorEndpointsAreClosedToVisitors() throws Exception {
    // Only health and info are public; the rest needs the operator role (see authz-matrix.md).
    assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(401);
  }

  @Test
  void operatorSeesOnlyTheExposedActuatorEndpoints() throws Exception {
    // the scrape itself, with metrics export on, is in PrometheusEndpointIT
    assertThat(getAsOperator("/actuator/env").statusCode()).isEqualTo(404);
    assertThat(getAsOperator("/actuator/info").statusCode()).isEqualTo(200);
  }

  /** The Helm chart's terminationGracePeriodSeconds (30s) assumes these values. */
  @Test
  void shutsDownGracefullyWithinTheKubernetesGracePeriod() {
    assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("20s");
  }
}
