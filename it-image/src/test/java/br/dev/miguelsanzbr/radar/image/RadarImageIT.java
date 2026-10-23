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
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Sobe a imagem construida por {@code make image}; o perfil image mantem o build padrao sem ela.
 */
class RadarImageIT {

  private static final Network NETWORK = Network.newNetwork();

  private static final PostgreSQLContainer DB =
      new PostgreSQLContainer("postgres:18").withNetwork(NETWORK).withNetworkAliases("db");

  // same source as the Makefile and the compose file: RADAR_TAG in the environment
  private static final String IMAGE =
      "radar-licitacoes:" + System.getenv().getOrDefault("RADAR_TAG", "local");

  // the app service limit in infra/compose/docker-compose.yml: the JVM ergonomics (collector and
  // initial heap) depend on it, so the image has to start under the same limit
  private static final long MEM_LIMIT_BYTES = 640L * 1024 * 1024;

  // the failed container is removed before the catch in start(), so keep its output apart
  private static final ToStringConsumer BOOT_LOG = new ToStringConsumer();

  private static final GenericContainer<?> APP =
      new GenericContainer<>(IMAGE)
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(MEM_LIMIT_BYTES))
          // makes the JVM log whether it mapped the AOT cache (JAVA_TOOL_OPTIONS of the image
          // stays)
          .withEnv("JDK_JAVA_OPTIONS", "-Xlog:aot:stdout")
          .withLogConsumer(BOOT_LOG)
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
    try {
      APP.start();
    } catch (ContainerLaunchException e) {
      // the JVM can die at boot (AOT cache that cannot be loaded): show why instead of a timeout
      throw new IllegalStateException(
          "image did not come up; JVM output:\n" + BOOT_LOG.toUtf8String(), e);
    }
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
    assertThat(APP.getLogs())
        .contains("Using AOT-linked classes: true")
        .doesNotContain("cannot be loaded", "[error][aot]", "[warning][aot]");
  }

  @Test
  void reportsHealthyThroughTheImageHealthcheck() throws Exception {
    assertThat(
            APP.execInContainer("wget", "-qO-", "http://localhost:8080/actuator/health/liveness")
                .getStdout())
        .contains("UP");
  }
}
