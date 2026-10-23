package br.dev.miguelsanzbr.radar.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp.PncpHttpGateway;
import br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp.PncpProperties;
import br.dev.miguelsanzbr.radar.ingest.application.PageFetcher;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform pool against virtual threads reading 200 pages that each answer in 200 ms. Run it with
 * {@code ./mvnw -Pbench test -pl core -am -Dtest=VirtualThreadsBenchTest}; the table lands in
 * {@code core/target/virtual-threads-bench.md}. The rate limiter is set far above any row's
 * throughput on purpose: here the only limits are the bulkhead and the pool, so the table shows the
 * threading effect and nothing else (the real client keeps the limiter as the cap).
 */
@Tag("bench")
class VirtualThreadsBenchTest {

  private static final int PAGES = 200;
  private static final Duration LATENCY = Duration.ofMillis(200);
  private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
  private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

  private static WireMockServer pncp;

  /** One measured row of the report. */
  private record Row(String strategy, int bulkhead, long millis, int extraThreads) {}

  @BeforeAll
  static void startPncp() {
    pncp = new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(64));
    pncp.start();
    for (var page = 1; page <= PAGES; page++) {
      pncp.stubFor(
          get(urlPathEqualTo(BackfillTestSupport.PUBLICATION))
              .withQueryParam("pagina", equalTo(String.valueOf(page)))
              .willReturn(
                  aResponse()
                      .withFixedDelay((int) LATENCY.toMillis())
                      .withHeader("Content-Type", "application/json")
                      .withBody(BackfillTestSupport.pageJson(page, PAGES, page))));
    }
  }

  @AfterAll
  static void stopPncp() {
    pncp.stop();
  }

  private static PncpHttpGateway gateway(int bulkhead) {
    var props =
        new PncpProperties(
            pncp.baseUrl(),
            50,
            100_000,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            new PncpProperties.Retry(1, Duration.ofSeconds(1), 2, 0.5),
            new PncpProperties.CircuitBreaker(
                20, 50, Duration.ofSeconds(60), Duration.ofSeconds(60)),
            bulkhead,
            Duration.ofMinutes(10),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30));
    return new PncpHttpGateway(
        props, JsonMapper.builder().build(), new SimpleMeterRegistry(), Clock.systemUTC());
  }

  /** Reads every page through the fetcher, in order, like the use cases do. */
  private static void readAll(PncpHttpGateway gateway, PageFetcher fetcher) {
    try (var pages = fetcher.open(1, p -> gateway.publications(DAY, DAY, 6, p))) {
      var expected = 1;
      while (expected <= PAGES) {
        var page = pages.next();
        assertThat(page.pageNumber()).isEqualTo(expected++);
      }
    }
  }

  private static void readSequentially(PncpHttpGateway gateway) {
    for (var page = 1; page <= PAGES; page++) {
      assertThat(gateway.publications(DAY, DAY, 6, page).pageNumber()).isEqualTo(page);
    }
  }

  private static Row measure(String strategy, int bulkhead, Runnable run) {
    System.gc();
    var before = THREADS.getThreadCount();
    THREADS.resetPeakThreadCount();
    var started = System.nanoTime();
    run.run();
    var millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    return new Row(strategy, bulkhead, millis, THREADS.getPeakThreadCount() - before);
  }

  private static Row platform(String label, int bulkhead, int poolSize) {
    try (var gateway = gateway(bulkhead);
        var pool = Executors.newFixedThreadPool(poolSize);
        var fetcher = new PageFetcher(pool, poolSize)) {
      return measure(label, bulkhead, () -> readAll(gateway, fetcher));
    }
  }

  private static Row virtual(String label, int bulkhead, int fetchAhead) {
    try (var gateway = gateway(bulkhead);
        var fetcher = PageFetcher.virtualThreads(fetchAhead)) {
      return measure(label, bulkhead, () -> readAll(gateway, fetcher));
    }
  }

  private static void warmUp() {
    try (var gateway = gateway(16);
        var fetcher = PageFetcher.virtualThreads(16)) {
      try (var pages = fetcher.open(1, p -> gateway.publications(DAY, DAY, 6, p))) {
        for (var i = 0; i < 48; i++) {
          pages.next();
        }
      }
    }
  }

  @Test
  void platformPoolAgainstVirtualThreadsOverTwoHundredPages() throws Exception {
    warmUp();
    var rows = new ArrayList<Row>();
    try (var gateway = gateway(4)) {
      rows.add(measure("sequential (today)", 1, () -> readSequentially(gateway)));
    }
    for (var bulkhead : List.of(4, 16)) {
      rows.add(platform("platform pool = bulkhead", bulkhead, bulkhead));
      rows.add(virtual("virtual, ahead = bulkhead", bulkhead, bulkhead));
      rows.add(platform("platform pool of " + PAGES, bulkhead, PAGES));
      rows.add(virtual("virtual, ahead = " + PAGES, bulkhead, PAGES));
    }

    var table =
        new StringBuilder("| strategy | bulkhead | wall time (ms) | extra threads at peak |\n");
    table.append("|---|---|---|---|\n");
    rows.forEach(
        r ->
            table.append(
                "| %s | %s | %d | %d |%n"
                    .formatted(
                        r.strategy(),
                        r.bulkhead() == 1 ? "-" : r.bulkhead(),
                        r.millis(),
                        r.extraThreads())));
    var report = table.toString();
    System.out.println(report);
    Files.writeString(Path.of("target/virtual-threads-bench.md"), report);

    var sequential = rows.getFirst();
    // every concurrent row beats the sequential read, and no virtual row needs a pool of threads
    assertThat(rows.stream().skip(1))
        .allSatisfy(r -> assertThat(r.millis()).isLessThan(sequential.millis()));
    assertThat(rows.stream().filter(r -> r.strategy().startsWith("virtual")))
        .allSatisfy(r -> assertThat(r.extraThreads()).isLessThan(40));
  }
}
