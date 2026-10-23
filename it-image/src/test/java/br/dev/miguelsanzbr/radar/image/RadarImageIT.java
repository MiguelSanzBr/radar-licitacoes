package br.dev.miguelsanzbr.radar.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Sobe a imagem construida por {@code make image}; o perfil image mantem o build padrao sem ela.
 */
class RadarImageIT {

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer DB =
      new PostgreSQLContainer("postgres:18").withNetwork(NETWORK).withNetworkAliases("db");

  private static final GenericContainer<?> APP =
      new GenericContainer<>("radar-licitacoes:local")
          .withNetwork(NETWORK)
          .withEnv("SPRING_PROFILES_ACTIVE", "noop")
          .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/" + DB.getDatabaseName())
          .withEnv("SPRING_DATASOURCE_USERNAME", DB.getUsername())
          .withEnv("SPRING_DATASOURCE_PASSWORD", DB.getPassword())
          // the json profile of the image has no default for the read side password
          .withEnv("RADAR_READ_WRITER_PASSWORD", "image-it-read-writer")
          // the schedulers are on by default: keep them off and the PNCP client unroutable, so this
          // test can never reach the real PNCP
          .withEnv("RADAR_INGEST_INCREMENTAL_ENABLED", "false")
          .withEnv("RADAR_INGEST_CLOSE_EXPIRED_ENABLED", "false")
          .withEnv("RADAR_PNCP_BASE_URL", "http://127.0.0.1:9")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/actuator/health/liveness").forPort(8080).forStatusCode(200));

  @BeforeAll
  static void start() {
    DB.start();
    APP.start();
  }

  @AfterAll
  static void stop() {
    APP.stop();
    DB.stop();
    NETWORK.close();
  }

  private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
    URI uri = URI.create("http://" + APP.getHost() + ":" + APP.getMappedPort(8080) + path);
    return HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void livenessAndReadinessReportUp() throws Exception {
    assertThat(get("/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
    assertThat(get("/actuator/health/readiness").body()).contains("\"status\":\"UP\"");
  }

  @Test
  void runsAsNonRootUser() throws Exception {
    assertThat(APP.execInContainer("id", "-u").getStdout().trim()).isEqualTo("10001");
  }

  @Test
  void usesTheAotCache() throws Exception {
    assertThat(APP.execInContainer("test", "-s", "/app/app.aot").getExitCode()).isZero();
    assertThat(APP.getLogs()).doesNotContain("[warning][aot]");
  }

  @Test
  void reportsHealthyThroughTheImageHealthcheck() throws Exception {
    assertThat(
            APP.execInContainer("wget", "-qO-", "http://localhost:8080/actuator/health/liveness")
                .getStdout())
        .contains("UP");
  }
}
