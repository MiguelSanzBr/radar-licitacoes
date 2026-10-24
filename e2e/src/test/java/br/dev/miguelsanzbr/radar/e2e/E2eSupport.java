package br.dev.miguelsanzbr.radar.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.testcontainers.containers.ComposeContainer;

/** What the compose end-to-end tests share: HTTP calls, SQL through the postgres service, waits. */
final class E2eSupport {

  static final Path REPO = Path.of(System.getProperty("radar.repo"));
  // same source as the Makefile and the compose file: RADAR_TAG in the environment
  static final String TAG = System.getenv().getOrDefault("RADAR_TAG", "local");
  static final String DAY = "2026-10-21";
  static final int ITEMS_IN_FIXTURE = 1633;
  static final String BACKFILL =
      "/ops/backfill?from=" + DAY + "&to=" + DAY + "&modality=6&force=true";

  private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private E2eSupport() {}

  static String operatorToken() throws Exception {
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

  static int post(String url, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  static HttpResponse<String> getResponse(String url) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }

  static boolean get(String url) {
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
  static String query(String promql) {
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

  static String sql(ComposeContainer compose, String statement) {
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

  static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
    var deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition not met in %s", timeout).isLessThan(deadline);
      Thread.sleep(1000);
    }
  }

  static int run(String... command) throws IOException, InterruptedException {
    return new ProcessBuilder(command)
        .directory(REPO.toFile())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .start()
        .waitFor();
  }
}
