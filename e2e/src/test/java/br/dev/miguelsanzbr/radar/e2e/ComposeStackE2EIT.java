package br.dev.miguelsanzbr.radar.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

/**
 * O compose inteiro (infra/compose) com a imagem {@code radar-licitacoes:local} (make image): sobe,
 * carrega o dia da fixture pelo backfill do operador e confere o resultado no banco, no outbox e no
 * Prometheus do otel-lgtm. Usa as portas fixas do compose (8080, 8180, 3000): pare o ambiente de
 * desenvolvimento (make down) antes.
 *
 * <p>Quando a busca e os alertas existirem, trocar a conferência por SQL por uma busca na API (GET
 * /api/v1/procurements) e acrescentar o alerta que chega ao Mailpit (porta 8025).
 */
class ComposeStackE2EIT {

  private static final Path REPO = Path.of(System.getProperty("radar.repo"));
  // same source as the Makefile and the compose file: RADAR_TAG in the environment
  private static final String TAG = System.getenv().getOrDefault("RADAR_TAG", "local");
  private static final String IMAGE = "radar-licitacoes:" + TAG;
  private static final String DAY = "2026-10-21";
  private static final int ITEMS_IN_FIXTURE = 1633;
  private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static ComposeContainer compose;

  @BeforeAll
  static void startStack() throws Exception {
    run(REPO.resolve("scripts/compose-init.sh").toString());
    assertThat(run("docker", "image", "inspect", IMAGE))
        .as("imagem %s ausente: rode `make image`", IMAGE)
        .isZero();
    compose =
        new ComposeContainer(
                "radar-e2e", new File(REPO.toFile(), "infra/compose/docker-compose.yml"))
            .withEnv("RADAR_TAG", TAG)
            .withPull(false)
            .withBuild(false)
            .withRemoveVolumes(true)
            .withStartupTimeout(Duration.ofMinutes(10));
    compose.start();
  }

  @AfterAll
  static void stopStack() {
    if (compose != null) {
      compose.stop();
    }
  }

  @Test
  void backfillOfTheFixtureDayReachesDatabaseKafkaAndMetrics() throws Exception {
    await(Duration.ofMinutes(3), () -> get("http://localhost:8080/actuator/health/readiness"));

    assertThat(post("/ops/backfill?from=" + DAY + "&to=" + DAY + "&modality=6&force=true", null))
        .as("sem token o /ops responde 401")
        .isEqualTo(401);

    var token = operatorToken();
    assertThat(post("/ops/backfill?from=" + DAY + "&to=" + DAY + "&modality=6&force=true", token))
        .isEqualTo(202);

    await(
        Duration.ofMinutes(3),
        () -> sql("select count(*) from ingest.procurement").equals("" + ITEMS_IN_FIXTURE));
    await(
        Duration.ofMinutes(1),
        () ->
            sql("select status from ingest.ingestion_run where kind = 'BACKFILL'").equals("DONE"));
    await(
        Duration.ofMinutes(2),
        () ->
            sql("select count(*) from outbox.outbox_event where published_at is null").equals("0")
                && !sql("select count(*) from outbox.outbox_event").equals("0"));

    // the read-side role was created by V6 with the secret file's password, over TCP (scram)
    var readWriterPassword =
        Files.readString(REPO.resolve("infra/compose/secrets/read_writer_password")).trim();
    var login =
        compose
            .getContainerByServiceName("postgres")
            .orElseThrow()
            .execInContainer(
                "env",
                "PGPASSWORD=" + readWriterPassword,
                "psql",
                "-h",
                "127.0.0.1",
                "-U",
                "radar_read_writer",
                "-d",
                "radar",
                "-tA",
                "-c",
                "select current_user");
    assertThat(login.getStdout().trim()).isEqualTo("radar_read_writer");

    // metrics are pushed over OTLP to otel-lgtm and read back through Grafana's Prometheus
    await(
        Duration.ofMinutes(3),
        () ->
            query("ingest_items_total").contains("\"discovered\"")
                && query("pncp_items_fetched_total").contains("\"publicacao\""));
  }

  private static String operatorToken() throws Exception {
    var form =
        "grant_type=client_credentials&client_id=radar-ops-cli&client_secret=radar-ops-dev-secret";
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:8180/realms/radar/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    var body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    var matcher = TOKEN.matcher(body);
    assertThat(matcher.find()).as("token response: %s", body).isTrue();
    return matcher.group(1);
  }

  private static int post(String path, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:8080" + path))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static boolean get(String url) {
    try {
      var request = HttpRequest.newBuilder(URI.create(url)).build();
      return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Prometheus series as JSON, through the datasource proxy of Grafana (admin/admin in dev). */
  private static String query(String promql) {
    try {
      var url =
          "http://localhost:3000/api/datasources/proxy/uid/prometheus/api/v1/query?query="
              + URLEncoder.encode(promql, StandardCharsets.UTF_8);
      var auth = Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
      var request =
          HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic " + auth).build();
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static String sql(String statement) {
    try {
      var postgres = compose.getContainerByServiceName("postgres").orElseThrow();
      var result =
          postgres.execInContainer("psql", "-U", "radar", "-d", "radar", "-tA", "-c", statement);
      return result.getStdout().trim();
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static void await(Duration timeout, BooleanSupplier condition)
      throws InterruptedException {
    var deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition not met in %s", timeout).isLessThan(deadline);
      Thread.sleep(1000);
    }
  }

  private static int run(String... command) throws IOException, InterruptedException {
    return new ProcessBuilder(command)
        .directory(REPO.toFile())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .start()
        .waitFor();
  }
}
