package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaTestConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"radar.outbox.relay-enabled=false"})
@AutoConfigureMetrics
@Import({PostgresTestConfig.class, KafkaTestConfig.class})
class PrometheusEndpointIT {

  @LocalServerPort int port;

  /** The scrape needs the operator role (ADR-0019); the token is decoded by this stub. */
  @MockitoBean JwtDecoder decoder;

  @BeforeEach
  void operatorToken() {
    when(decoder.decode("operator"))
        .thenReturn(
            Jwt.withTokenValue("operator")
                .header("alg", "RS256")
                .subject("prometheus")
                .claim("realm_access", Map.of("roles", List.of("operator")))
                .build());
  }

  private HttpResponse<String> request(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer operator")
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void scrapeIsClosedToVisitors() throws Exception {
    var anonymous =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/prometheus"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(anonymous.statusCode()).isEqualTo(401);
  }

  private String get(String path) throws Exception {
    var response = request(path);
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  @Test
  void otherSensitiveEndpointsStayUnexposed() throws Exception {
    assertThat(request("/actuator/env").statusCode()).isEqualTo(404);
    assertThat(request("/actuator/beans").statusCode()).isEqualTo(404);
  }

  @Test
  void scrapeListsTheMetricsOfTheDashboardsUnderTheirExactNames() throws Exception {
    get("/actuator/health");

    var scrape = get("/actuator/prometheus");

    assertThat(scrape)
        .contains("# TYPE outbox_pending gauge")
        .contains("# TYPE outbox_oldest_age_seconds gauge")
        .contains("# TYPE outbox_poison_total counter")
        .contains("# TYPE pncp_items_fetched_total counter")
        .contains("# TYPE dlq_messages_total counter")
        .contains(
            "dlq_messages_total{application=\"radar\",topic=\"radar.procurement.v1.dlq\"} 0.0")
        .contains("dlq_messages_total{application=\"radar\",topic=\"radar.alert.v1.dlq\"} 0.0")
        .containsPattern("outbox_pending\\{application=\"radar\"} \\d")
        .contains("http_server_requests_seconds_count{")
        .contains("http_server_requests_seconds_sum{")
        .contains("http_server_requests_seconds_bucket{");
  }
}
