package br.dev.miguelsanzbr.radar.probe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private WireMockServer server;

  @BeforeEach
  void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private int cli(String... args) throws IOException {
    return Cli.run(
        args,
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  private List<String> lines(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      List<String> out = new ArrayList<>();
      for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
        out.addAll(Files.readAllLines(f));
      }
      return out;
    }
  }

  @Test
  void runRecordsRequestsToATimestampedJsonlAndSurvivesARobotsReset(@TempDir Path out)
      throws IOException {
    server.stubFor(
        get(anyUrl())
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"data\":[],\"totalRegistros\":0,\"totalPaginas\":0}")));
    server.stubFor(
        get(urlEqualTo("/robots.txt"))
            .atPriority(1)
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    int exit =
        cli(
            "run",
            "--duration=PT3S",
            "--out=" + out.resolve("nested"),
            "--base-url=http://localhost:" + server.port(),
            "--zone=UTC",
            "ignored",
            "--no-value",
            "--zone=UTC");

    assertThat(exit).isZero();
    try (Stream<Path> files = Files.list(out.resolve("nested"))) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .singleElement()
          .asString()
          .matches("requests-\\d{8}-\\d{4}\\.jsonl");
    }
    List<String> lines = lines(out.resolve("nested"));
    assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
    assertThat(lines.get(1)).contains("\"job\":\"S9\"").contains("\"status\":-1");
    assertThat(stdout.toString(StandardCharsets.UTF_8))
        .contains("sonda iniciada")
        .contains("sonda encerrada: " + lines.size() + " requisições, parada: nenhuma");
  }

  @Test
  void runExitsWithCodeTwoOnTheFirstRateLimit(@TempDir Path out) throws IOException {
    server.stubFor(
        get(anyUrl()).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));

    int exit =
        cli(
            "run",
            "--duration=PT1M",
            "--out=" + out,
            "--base-url=http://localhost:" + server.port());

    assertThat(exit).isEqualTo(2);
    assertThat(lines(out))
        .hasSize(1)
        .first()
        .asString()
        .contains("\"status\":429")
        .contains("retry-after");
    assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("parada: HTTP 429");
  }

  @Test
  void reportWritesTheMarkdownFromTheRecordedFiles(@TempDir Path dir) throws IOException {
    Files.copy(Path.of("src/test/resources/full.jsonl"), dir.resolve("requests-1.jsonl"));
    Path target = dir.resolve("out/report.md");

    int exit = cli("report", "--in=" + dir, "--to=" + target, "--zone=UTC");

    assertThat(exit).isZero();
    assertThat(Files.readString(target)).contains("## S3 Rampa de taxa");
    assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("relatório gravado em " + target);
  }

  @Test
  void unknownOrMissingModePrintsUsageAndFails() throws IOException {
    assertThat(cli()).isEqualTo(1);
    assertThat(cli("bogus")).isEqualTo(1);
    assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("uso: pncp-probe run|report");
  }

  @Test
  void exitHookIsCalledOnlyForAFailingRun(@TempDir Path out) throws IOException {
    List<Integer> exits = new ArrayList<>();
    server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(403)));
    PrintStream quiet = new PrintStream(stdout, true, StandardCharsets.UTF_8);

    Cli.exitOnFailure(new String[] {"bogus"}, quiet, quiet, exits::add);
    Cli.exitOnFailure(
        new String[] {
          "run", "--duration=PT1M", "--out=" + out, "--base-url=http://localhost:" + server.port()
        },
        quiet,
        quiet,
        exits::add);

    assertThat(exits).containsExactly(1, 2);
  }

  @Test
  void aSuccessfulRunLeavesTheJvmToEndNormally(@TempDir Path dir) throws IOException {
    Files.copy(Path.of("src/test/resources/full.jsonl"), dir.resolve("requests-1.jsonl"));

    PncpProbe.main(new String[] {"report", "--in=" + dir, "--to=" + dir.resolve("r.md")});

    assertThat(dir.resolve("r.md")).exists();
  }
}
