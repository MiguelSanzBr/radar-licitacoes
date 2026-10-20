package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpProtocolException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

class PncpHttpGatewayTest {

  private static final String PUBLICATION = "/api/consulta/v1/contratacoes/publicacao";
  private static final String UPDATE = "/api/consulta/v1/contratacoes/atualizacao";
  private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

  /** Counts requests being served at the same time, holding each one for a while. */
  static final class ConcurrencyProbe implements ResponseDefinitionTransformerV2 {
    final AtomicInteger current = new AtomicInteger();
    final AtomicInteger max = new AtomicInteger();

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
      max.accumulateAndGet(current.incrementAndGet(), Math::max);
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        current.decrementAndGet();
      }
      return serveEvent.getResponseDefinition();
    }

    @Override
    public String getName() {
      return "concurrency-probe";
    }

    @Override
    public boolean applyGlobally() {
      return false;
    }
  }

  static final ConcurrencyProbe PROBE = new ConcurrencyProbe();

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance()
          .options(wireMockConfig().dynamicPort().extensions(PROBE))
          .build();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private PncpHttpGateway gateway;

  @AfterEach
  void close() {
    if (gateway != null) {
      gateway.close();
    }
  }

  private PncpProperties props(
      Duration timeout, int attempts, int breakerWindow, double rps, int bulkhead) {
    return props(
        timeout,
        attempts,
        new PncpProperties.CircuitBreaker(
            breakerWindow, 50f, Duration.ofSeconds(60), Duration.ofSeconds(10)),
        rps,
        bulkhead,
        Duration.ofSeconds(10),
        Duration.ofSeconds(10));
  }

  private PncpProperties props(
      Duration timeout,
      int attempts,
      PncpProperties.CircuitBreaker breaker,
      double rps,
      int bulkhead,
      Duration bulkheadWait,
      Duration rateWait) {
    return new PncpProperties(
        wm.baseUrl(),
        50,
        rps,
        timeout,
        Duration.ofSeconds(2),
        new PncpProperties.Retry(attempts, Duration.ofMillis(10), 2.0, 0.5),
        breaker,
        bulkhead,
        bulkheadWait,
        rateWait);
  }

  private PncpHttpGateway gateway(PncpProperties properties) {
    gateway = new PncpHttpGateway(properties, JsonMapper.builder().build(), meters);
    return gateway;
  }

  private PncpHttpGateway lenientGateway() {
    return gateway(props(Duration.ofSeconds(5), 4, 100, 1000, 4));
  }

  private static void report(String scenario, long started) {
    System.out.printf(
        "SCENARIO|%s|requests=%d|elapsedMs=%d%n",
        scenario, wm.getAllServeEvents().size(), (System.nanoTime() - started) / 1_000_000);
  }

  private static void sequence(ResponseDefinitionBuilder... responses) {
    for (int i = 0; i < responses.length; i++) {
      wm.stubFor(
          get(urlPathEqualTo(PUBLICATION))
              .inScenario("seq")
              .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "s" + i)
              .willSetStateTo("s" + (i + 1))
              .willReturn(responses[i]));
    }
  }

  private static String body(String name) throws IOException {
    return PncpMapperTest.fixture(name);
  }

  @Test
  void paginatedPagesAreMappedAndTheLastIsFlagged() throws IOException {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .withQueryParam("pagina", equalTo("1"))
            .willReturn(okJson(body("publicacao-page1.json"))));
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .withQueryParam("pagina", equalTo("2"))
            .willReturn(okJson(body("publicacao-page2.json"))));
    var started = System.nanoTime();

    var first = lenientGateway().publications(DAY, DAY, 6, 1);
    var second = gateway.publications(DAY, DAY, 6, 2);

    report("200 paginado", started);
    assertThat(first.items()).hasSize(3);
    assertThat(first.isLast()).isFalse();
    assertThat(first.items().get(0).id().value()).isEqualTo("83021808000182-1-000681/2026");
    assertThat(second.items()).hasSize(1);
    assertThat(second.isLast()).isTrue();
    assertThat(second.items().get(0).unit().uf().name()).isEqualTo("PA");
    wm.verify(
        getRequestedFor(urlPathEqualTo(PUBLICATION))
            .withQueryParam("dataInicial", equalTo("20261020"))
            .withQueryParam("dataFinal", equalTo("20261020"))
            .withQueryParam("codigoModalidadeContratacao", equalTo("6"))
            .withQueryParam("tamanhoPagina", equalTo("50"))
            .withHeader("User-Agent", equalTo(PncpHttpGateway.USER_AGENT))
            .withHeader("Accept", equalTo("application/json")));
    assertThat(meters.counter("pncp_items_fetched_total", "endpoint", "publicacao").count())
        .isEqualTo(4);
    assertThat(
            meters
                .timer("pncp_request_duration", "endpoint", "publicacao", "outcome", "ok")
                .count())
        .isEqualTo(2);
  }

  @Test
  void updatesUseTheirOwnEndpoint() throws IOException {
    wm.stubFor(get(urlPathEqualTo(UPDATE)).willReturn(okJson(body("publicacao-page2.json"))));

    var page = lenientGateway().updates(DAY, DAY.plusDays(1), 6, 2);

    assertThat(page.items()).hasSize(1);
    wm.verify(
        getRequestedFor(urlPathEqualTo(UPDATE)).withQueryParam("dataFinal", equalTo("20261021")));
    assertThat(meters.counter("pncp_items_fetched_total", "endpoint", "atualizacao").count())
        .isEqualTo(1);
  }

  @Test
  void noContentIsAnEmptyPage() {
    wm.stubFor(get(urlPathEqualTo(PUBLICATION)).willReturn(aResponse().withStatus(204)));
    var started = System.nanoTime();

    var page = lenientGateway().publications(DAY, DAY, 6, 3);

    report("204 sem dados", started);
    assertThat(page).isEqualTo(new PncpPage(List.of(), 3, 0, 0, 0));
    assertThat(page.isLast()).isTrue();
  }

  @Test
  void tooManyRequestsWaitsAtLeastRetryAfter() throws IOException {
    sequence(
        aResponse().withStatus(429).withHeader("Retry-After", "1"),
        okJson(body("publicacao-page2.json")));
    var started = System.nanoTime();

    var page = lenientGateway().publications(DAY, DAY, 6, 1);

    var elapsed = Duration.ofNanos(System.nanoTime() - started);
    report("429 Retry-After 1 s", started);
    assertThat(page.items()).hasSize(1);
    assertThat(wm.getAllServeEvents()).hasSize(2);
    assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void tooManyRequestsWithoutAUsableRetryAfterUsesTheBackoff() throws IOException {
    sequence(
        aResponse().withStatus(429).withHeader("Retry-After", "soon"),
        okJson(body("publicacao-page2.json")));

    var page = lenientGateway().publications(DAY, DAY, 6, 1);

    assertThat(page.items()).hasSize(1);
    assertThat(wm.getAllServeEvents()).hasSize(2);
  }

  @Test
  void retryAfterIsParsedInSecondsAndCapped() {
    assertThat(PncpHttpGateway.retryAfterMillis(headers("Retry-After", "2"))).isEqualTo(2000);
    assertThat(PncpHttpGateway.retryAfterMillis(headers("Retry-After", "99999")))
        .isEqualTo(300_000);
    assertThat(PncpHttpGateway.retryAfterMillis(headers("Retry-After", "x"))).isZero();
    assertThat(PncpHttpGateway.retryAfterMillis(headers("Other", "1"))).isZero();
  }

  private static HttpHeaders headers(String name, String value) {
    return HttpHeaders.of(Map.of(name, List.of(value)), (a, b) -> true);
  }

  @Test
  void serverErrorsAreRetriedUntilSuccess() throws IOException {
    sequence(
        aResponse().withStatus(500),
        aResponse().withStatus(500),
        okJson(body("publicacao-page2.json")));
    var started = System.nanoTime();

    var page = lenientGateway().publications(DAY, DAY, 6, 1);

    report("500, 500, 200", started);
    assertThat(page.items()).hasSize(1);
    assertThat(wm.getAllServeEvents()).hasSize(3);
    assertThat(
            meters
                .timer(
                    "pncp_request_duration", "endpoint", "publicacao", "outcome", "retryable_error")
                .count())
        .isEqualTo(2);
  }

  @Test
  void exhaustedRetriesRaiseTheLastStatus() {
    wm.stubFor(get(urlPathEqualTo(PUBLICATION)).willReturn(aResponse().withStatus(503)));
    var started = System.nanoTime();

    assertThatThrownBy(() -> lenientGateway().publications(DAY, DAY, 6, 1))
        .isInstanceOfSatisfying(
            PncpUnavailableException.class, e -> assertThat(e.status()).isEqualTo(503));

    report("503 x4 esgota tentativas", started);
    assertThat(wm.getAllServeEvents()).hasSize(4);
  }

  @Test
  void connectionFaultsAreRetriedThenRaised() {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThatThrownBy(
            () ->
                gateway(props(Duration.ofSeconds(5), 2, 100, 1000, 4)).publications(DAY, DAY, 6, 1))
        .isInstanceOfSatisfying(
            PncpUnavailableException.class,
            e -> {
              assertThat(e.status()).isZero();
              assertThat(e).hasCauseInstanceOf(IOException.class);
            });

    assertThat(wm.getAllServeEvents()).hasSize(2);
  }

  @Test
  void clientErrorsAreNotRetried() {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withStatus(400).withBody("parametro invalido")));
    var started = System.nanoTime();

    assertThatThrownBy(() -> lenientGateway().publications(DAY, DAY, 6, 1))
        .isInstanceOfSatisfying(
            PncpProtocolException.class,
            e -> {
              assertThat(e.status()).isEqualTo(400);
              assertThat(e).hasMessageContaining("400").hasMessageContaining("parametro invalido");
            });

    report("400 nao retentavel", started);
    assertThat(wm.getAllServeEvents()).hasSize(1);
  }

  @Test
  void mappingFailuresAreNotRetried() throws IOException {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(
                okJson(
                    body("publicacao-page1.json")
                        .replace(
                            "\"dataPublicacaoPncp\": \"2026-10-20T00:00:36\"",
                            "\"dataPublicacaoPncp\": \"2026-10-20T00:00:36Z\""))));

    assertThatThrownBy(() -> lenientGateway().publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpProtocolException.class);

    assertThat(wm.getAllServeEvents()).hasSize(1);
    assertThat(
            meters
                .timer(
                    "pncp_request_duration", "endpoint", "publicacao", "outcome", "mapping_error")
                .count())
        .isEqualTo(1);
  }

  @Test
  void slowResponsesTimeOutAndTheCircuitOpensAndFailsFast() {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withFixedDelay(15_000).withStatus(200)));
    var g = gateway(props(Duration.ofSeconds(1), 1, 2, 1000, 4));
    var started = System.nanoTime();

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOfSatisfying(
            PncpUnavailableException.class, e -> assertThat(e.status()).isZero());
    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpUnavailableException.class);
    var before = wm.getAllServeEvents().size();
    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpUnavailableException.class)
        .hasCauseInstanceOf(CallNotPermittedException.class);

    report("lentidao 15 s: timeout 1 s, circuito abre apos 2 falhas", started);
    assertThat(before).isEqualTo(2);
    assertThat(wm.getAllServeEvents()).hasSize(2);
  }

  @Test
  void rateLimiterSpacesTheCalls() {
    wm.stubFor(get(urlPathEqualTo(PUBLICATION)).willReturn(aResponse().withStatus(204)));
    var g = gateway(props(Duration.ofSeconds(5), 1, 100, 2, 4));
    g.publications(DAY, DAY, 6, 1); // consumes the permit that exists at start
    var started = System.nanoTime();

    for (int i = 0; i < 5; i++) {
      g.publications(DAY, DAY, 6, 1);
    }

    var elapsed = Duration.ofNanos(System.nanoTime() - started);
    report("5 chamadas a 2 req/s", started);
    assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void bulkheadLimitsConcurrentRequests() throws Exception {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withStatus(204).withTransformers("concurrency-probe")));
    var g = gateway(props(Duration.ofSeconds(5), 1, 100, 1000, 2));
    var started = System.nanoTime();

    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<PncpPage>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        futures.add(pool.submit((Callable<PncpPage>) () -> g.publications(DAY, DAY, 6, 1)));
      }
      for (var f : futures) {
        f.get();
      }
    }

    report("bulkhead 2 permissoes, 6 chamadas", started);
    assertThat(wm.getAllServeEvents()).hasSize(6);
    assertThat(PROBE.max.get()).isEqualTo(2);
  }

  @Test
  void interruptionCancelsTheCallAndKeepsTheFlag() throws Exception {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withFixedDelay(3_000).withStatus(204)));
    var g = lenientGateway();
    var failure = new AtomicReference<Throwable>();
    var flag = new AtomicReference<Boolean>();
    var started = new CountDownLatch(1);
    var thread =
        Thread.ofPlatform()
            .start(
                () -> {
                  started.countDown();
                  try {
                    g.publications(DAY, DAY, 6, 1);
                  } catch (RuntimeException e) {
                    failure.set(e);
                    flag.set(Thread.currentThread().isInterrupted());
                  }
                });
    started.await();
    Thread.sleep(300);
    thread.interrupt();
    thread.join(5_000);

    assertThat(failure.get()).isInstanceOf(CancellationException.class);
    assertThat(flag.get()).isTrue();
  }

  @Test
  void retriesConsumeRatePermits() {
    wm.stubFor(get(urlPathEqualTo(UPDATE)).willReturn(aResponse().withStatus(204)));
    sequence(aResponse().withStatus(500), aResponse().withStatus(204));
    var g = gateway(props(Duration.ofSeconds(5), 2, 100, 2, 4));
    g.updates(DAY, DAY, 6, 1); // takes the permit available at start
    var started = System.nanoTime();

    g.publications(DAY, DAY, 6, 1);

    var times =
        wm.getAllServeEvents().stream()
            .filter(e -> e.getRequest().getUrl().startsWith(PUBLICATION))
            .map(e -> e.getRequest().getLoggedDate().getTime())
            .sorted()
            .toList();
    report("retry consome permissao: 500 e 204 a 2 req/s", started);
    assertThat(times).hasSize(2);
    assertThat(times.get(1) - times.get(0)).isGreaterThanOrEqualTo(450);
  }

  @Test
  void rateLimiterRejectionIsReportedAsUnavailable() {
    wm.stubFor(get(urlPathEqualTo(PUBLICATION)).willReturn(aResponse().withStatus(204)));
    var g =
        gateway(
            props(
                Duration.ofSeconds(5),
                1,
                new PncpProperties.CircuitBreaker(
                    100, 50f, Duration.ofSeconds(60), Duration.ofSeconds(10)),
                1,
                4,
                Duration.ofSeconds(10),
                Duration.ZERO));
    g.publications(DAY, DAY, 6, 1);

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpUnavailableException.class)
        .hasCauseInstanceOf(RequestNotPermitted.class);
    assertThat(wm.getAllServeEvents()).hasSize(1);
  }

  @Test
  void bulkheadRejectionIsReportedAsUnavailable() throws Exception {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(aResponse().withStatus(204).withTransformers("concurrency-probe")));
    var g =
        gateway(
            props(
                Duration.ofSeconds(5),
                1,
                new PncpProperties.CircuitBreaker(
                    100, 50f, Duration.ofSeconds(60), Duration.ofSeconds(10)),
                1000,
                1,
                Duration.ZERO,
                Duration.ofSeconds(10)));

    var failures = new AtomicInteger();
    var causes = new AtomicReference<Throwable>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  try {
                    g.publications(DAY, DAY, 6, 1);
                  } catch (PncpUnavailableException e) {
                    failures.incrementAndGet();
                    causes.set(e.getCause());
                  }
                }));
      }
      for (var f : futures) {
        f.get();
      }
    }

    assertThat(failures).hasValue(1);
    assertThat(causes.get()).isInstanceOf(BulkheadFullException.class);
  }

  @Test
  void slowCallsAloneCanOpenTheCircuit() {
    // one slow and one fast call: 50% slow opens the circuit at a 50% rate, not at the default 100%
    sequence(aResponse().withFixedDelay(300).withStatus(204), aResponse().withStatus(204));
    var g =
        gateway(
            props(
                Duration.ofSeconds(5),
                1,
                new PncpProperties.CircuitBreaker(
                    2, 50f, Duration.ofSeconds(60), Duration.ofMillis(100)),
                1000,
                4,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10)));
    g.publications(DAY, DAY, 6, 1);
    g.publications(DAY, DAY, 6, 1);

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpUnavailableException.class)
        .hasCauseInstanceOf(CallNotPermittedException.class);
    assertThat(wm.getAllServeEvents()).hasSize(2);
  }

  @Test
  void requestTimeoutAlsoBoundsASlowBody() {
    wm.stubFor(
        get(urlPathEqualTo(PUBLICATION))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("x".repeat(2_000))
                    .withChunkedDribbleDelay(5, 10_000)));
    var g = gateway(props(Duration.ofSeconds(1), 1, 100, 1000, 4));
    var started = System.nanoTime();

    assertThatThrownBy(() -> g.publications(DAY, DAY, 6, 1))
        .isInstanceOf(PncpUnavailableException.class);

    report("corpo lento com timeout de 1 s", started);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
  }

  @Test
  void resilienceMetersAreBoundAndRequestTimeIsPlausible() {
    wm.stubFor(get(urlPathEqualTo(PUBLICATION)).willReturn(aResponse().withStatus(204)));

    lenientGateway().publications(DAY, DAY, 6, 1);

    assertThat(meters.find("resilience4j.circuitbreaker.state").gauges()).isNotEmpty();
    assertThat(meters.find("resilience4j.ratelimiter.available.permissions").gauges()).isNotEmpty();
    assertThat(meters.find("resilience4j.bulkhead.available.concurrent.calls").gauges())
        .isNotEmpty();
    assertThat(meters.find("resilience4j.retry.calls").functionCounters()).isNotEmpty();
    var timer = meters.timer("pncp_request_duration", "endpoint", "publicacao", "outcome", "ok");
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isBetween(0.0, 5.0);
  }
}
