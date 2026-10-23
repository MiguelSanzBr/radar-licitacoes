package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpBlockedException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpProtocolException;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * PNCP over {@code java.net.http}. Each attempt goes through, outermost first: retry, rate limiter,
 * bulkhead, circuit breaker, the call itself. The breaker only sees the call, so waiting for a rate
 * permit or a bulkhead slot never counts as slowness.
 *
 * <p>A 429 or 403 is a block, not a failure to retry: the call is not repeated and, until the pause
 * ends, every caller of this instance fails fast with {@link PncpBlockedException} without a
 * request. The check runs before the permits and again right before sending, so a call that was
 * already waiting for a permit when the block came does not go out either.
 */
@Component
@EnableConfigurationProperties(PncpProperties.class)
public class PncpHttpGateway implements PncpGateway, AutoCloseable {

  static final String USER_AGENT =
      "radar-licitacoes/0.1 (+https://github.com/miguelsanzbr/radar-licitacoes;"
          + " contato: miguelsanzb.p@gmail.com)";

  private static final Logger LOG = LoggerFactory.getLogger(PncpHttpGateway.class);
  private static final String PUBLICATION = "/api/consulta/v1/contratacoes/publicacao";
  private static final String UPDATE = "/api/consulta/v1/contratacoes/atualizacao";
  private static final List<String> ENDPOINTS = List.of("publicacao", "atualizacao");
  private static final List<String> OUTCOMES =
      List.of(
          "ok",
          "retryable_error",
          "blocked",
          "client_error",
          "mapping_error",
          "io_error",
          "interrupted");
  private static final long MAX_RETRY_WAIT_MILLIS = 300_000;
  private static final int BODY_EXCERPT = 200;

  /** The block in force, shared by every caller: what the PNCP answered and until when. */
  private record Block(int status, Instant until, Duration retryAfter) {

    PncpBlockedException exception() {
      return new PncpBlockedException(status, until, retryAfter);
    }
  }

  private final PncpProperties props;
  private final PncpMapper mapper;
  private final Clock clock;
  private final Map<String, Counter> itemCounters = new HashMap<>();
  private final Map<String, Timer> timers = new HashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient http;
  private final Retry retry;
  private final RateLimiter rateLimiter;
  private final Bulkhead bulkhead;
  private final CircuitBreaker breaker;
  private final AtomicReference<Block> blocked = new AtomicReference<>();

  public PncpHttpGateway(PncpProperties props, JsonMapper json, MeterRegistry meters, Clock clock) {
    this.props = props;
    this.clock = clock;
    this.mapper = new PncpMapper(json);
    for (var endpoint : ENDPOINTS) {
      itemCounters.put(endpoint, meters.counter("pncp_items_fetched_total", "endpoint", endpoint));
      for (var outcome : OUTCOMES) {
        timers.put(
            endpoint + "/" + outcome,
            Timer.builder("pncp_request_duration")
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .register(meters));
      }
    }
    this.http =
        HttpClient.newBuilder().executor(executor).connectTimeout(props.connectTimeout()).build();

    var rate =
        RateLimiterConfig.custom()
            .limitForPeriod(1)
            .limitRefreshPeriod(
                Duration.ofNanos((long) (1_000_000_000 / props.requestsPerSecond())))
            .timeoutDuration(props.rateLimiterMaxWait())
            .build();
    var rateRegistry = RateLimiterRegistry.of(rate);
    this.rateLimiter = rateRegistry.rateLimiter("pncp");

    var bulk =
        BulkheadConfig.custom()
            .maxConcurrentCalls(props.maxConcurrentCalls())
            .maxWaitDuration(props.bulkheadMaxWait())
            .build();
    var bulkRegistry = BulkheadRegistry.of(bulk);
    this.bulkhead = bulkRegistry.bulkhead("pncp");

    var cb = props.circuitBreaker();
    var breakerConfig =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(cb.windowSize())
            .minimumNumberOfCalls(cb.windowSize())
            .failureRateThreshold(cb.failureRatePercent())
            .waitDurationInOpenState(cb.openWait())
            .slowCallDurationThreshold(cb.slowCallThreshold())
            .slowCallRateThreshold(cb.failureRatePercent())
            .recordException(PncpHttpGateway::retryable)
            .ignoreExceptions(PncpBlockedException.class)
            .build();
    var breakerRegistry = CircuitBreakerRegistry.of(breakerConfig);
    this.breaker = breakerRegistry.circuitBreaker("pncp");

    var r = props.retry();
    var backoff =
        IntervalFunction.ofExponentialRandomBackoff(r.initialBackoff(), r.multiplier(), r.jitter());
    var retryConfig =
        RetryConfig.<PncpPage>custom()
            .maxAttempts(r.maxAttempts())
            .retryOnException(PncpHttpGateway::retryable)
            .intervalBiFunction(
                (attempt, outcome) ->
                    Math.max(
                        backoff.apply(attempt),
                        ((PncpRetryableException) outcome.getLeft()).retryAfterMillis()))
            .build();
    var retryRegistry = RetryRegistry.of(retryConfig);
    this.retry = retryRegistry.retry("pncp");

    TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateRegistry).bindTo(meters);
    TaggedBulkheadMetrics.ofBulkheadRegistry(bulkRegistry).bindTo(meters);
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakerRegistry).bindTo(meters);
    TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meters);
  }

  @Override
  public PncpPage publications(LocalDate from, LocalDate to, int modalityId, int page) {
    return call("publicacao", PUBLICATION, from, to, modalityId, page);
  }

  @Override
  public PncpPage updates(LocalDate from, LocalDate to, int modalityId, int page) {
    return call("atualizacao", UPDATE, from, to, modalityId, page);
  }

  @Override
  public void close() {
    http.close();
    executor.close();
  }

  private PncpPage call(
      String endpoint, String path, LocalDate from, LocalDate to, int modalityId, int page) {
    var uri =
        URI.create(
            props.baseUrl()
                + path
                + "?dataInicial="
                + DateTimeFormatter.BASIC_ISO_DATE.format(from)
                + "&dataFinal="
                + DateTimeFormatter.BASIC_ISO_DATE.format(to)
                + "&codigoModalidadeContratacao="
                + modalityId
                + "&pagina="
                + page
                + "&tamanhoPagina="
                + props.pageSize());
    failIfBlocked();
    Supplier<PncpPage> attempt = () -> fetch(endpoint, uri, page);
    attempt = CircuitBreaker.decorateSupplier(breaker, attempt);
    attempt = Bulkhead.decorateSupplier(bulkhead, attempt);
    attempt = RateLimiter.decorateSupplier(rateLimiter, attempt);
    try {
      return Retry.decorateSupplier(retry, attempt).get();
    } catch (CallNotPermittedException | RequestNotPermitted | BulkheadFullException e) {
      throw new PncpUnavailableException(0, "PNCP call rejected locally: " + e.getMessage(), e);
    }
  }

  private PncpPage fetch(String endpoint, URI uri, int page) {
    failIfBlocked();
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(props.requestTimeout())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    var started = System.nanoTime();
    var outcome = "io_error";
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      var status = response.statusCode();
      if (status == 200) {
        PncpPage result;
        try {
          result = mapper.page(response.body());
        } catch (PncpProtocolException e) {
          outcome = "mapping_error";
          throw e;
        }
        outcome = "ok";
        itemCounters.get(endpoint).increment(result.items().size());
        return result;
      }
      if (status == 204) {
        outcome = "ok";
        return new PncpPage(List.of(), page, 0, 0, 0);
      }
      var body = response.body();
      var excerpt = body.substring(0, Math.min(BODY_EXCERPT, body.length()));
      var message = "PNCP status " + status + ": " + excerpt;
      if (status == 429 || status == 403) {
        outcome = "blocked";
        var e = block(status, response.headers());
        LOG.warn("{}, response: {}", e.getMessage(), excerpt);
        throw e;
      }
      if (status >= 500) {
        outcome = "retryable_error";
        throw new PncpRetryableException(
            status, message, retryAfterMillis(response.headers(), clock.instant()), null);
      }
      outcome = "client_error";
      throw new PncpProtocolException(status, message, null);
    } catch (IOException e) {
      throw new PncpRetryableException(0, "PNCP unreachable: " + e, 0, e);
    } catch (InterruptedException e) {
      outcome = "interrupted";
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted while calling PNCP");
    } finally {
      timers
          .get(endpoint + "/" + outcome)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private static boolean retryable(Throwable t) {
    return t instanceof PncpRetryableException;
  }

  @Override
  public Optional<Instant> blockedUntil() {
    var b = blocked.get();
    return b != null && clock.instant().isBefore(b.until())
        ? Optional.of(b.until())
        : Optional.empty();
  }

  /**
   * Starts the pause shared by every caller: the {@code Retry-After} (never less than one rate
   * limiter interval), else the default pause. A block that ends earlier than the one in force does
   * not replace it, so a late answer cannot shorten the pause.
   */
  PncpBlockedException block(int status, HttpHeaders headers) {
    var now = clock.instant();
    var retryAfter = RetryAfter.parse(headers, now).orElse(null);
    var interval = Duration.ofNanos((long) (1_000_000_000 / props.requestsPerSecond()));
    var pause =
        retryAfter == null ? props.blockPause() : Collections.max(List.of(retryAfter, interval));
    var latest =
        blocked.accumulateAndGet(
            new Block(status, now.plus(pause), retryAfter),
            (current, next) ->
                current != null && !next.until().isAfter(current.until()) ? current : next);
    return latest.exception();
  }

  private void failIfBlocked() {
    if (blockedUntil().isPresent()) {
      throw blocked.get().exception();
    }
  }

  /** Wait asked by a retried error ({@code Retry-After}, seconds or date), at most 5 minutes. */
  static long retryAfterMillis(HttpHeaders headers, Instant now) {
    return RetryAfter.parse(headers, now)
        .map(wait -> Math.min(wait.toMillis(), MAX_RETRY_WAIT_MILLIS))
        .orElse(0L);
  }
}
