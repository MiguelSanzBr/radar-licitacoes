package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.EventPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LivenessProbeTest {

  @LocalServerPort int port;

  // the relay needs a publisher; the Kafka one arrives with PR 1.7
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
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString());
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
  void prometheusIsNotExposed() throws Exception {
    assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(404);
  }
}
