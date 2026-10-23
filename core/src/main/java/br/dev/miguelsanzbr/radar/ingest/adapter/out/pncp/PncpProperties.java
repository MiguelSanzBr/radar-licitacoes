package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client and resilience settings; the defaults are the policy of ADR-0017. The rate is 0.2 req/s
 * because the real PNCP answered 429 at a sustained 0.5 req/s (provisional until the probe
 * decides); it is the one cap on requests per second, whatever the page fetch concurrency. {@code
 * blockPause} is how long the client stays silent after a 429 or 403 that came without a usable
 * {@code Retry-After}.
 */
@ConfigurationProperties("radar.pncp")
public record PncpProperties(
    @DefaultValue("https://pncp.gov.br") String baseUrl,
    @DefaultValue("50") int pageSize,
    @DefaultValue("0.2") double requestsPerSecond,
    @DefaultValue("15s") Duration requestTimeout,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue Retry retry,
    @DefaultValue CircuitBreaker circuitBreaker,
    @DefaultValue("4") int maxConcurrentCalls,
    @DefaultValue("30s") Duration bulkheadMaxWait,
    @DefaultValue("120s") Duration rateLimiterMaxWait,
    @DefaultValue("PT30M") Duration blockPause) {

  public PncpProperties {
    check(pageSize >= 10, "pageSize must be >= 10");
    check(pageSize <= 50, "pageSize must be <= 50");
    check(requestsPerSecond > 0, "requestsPerSecond must be > 0");
    check(positive(requestTimeout), "requestTimeout must be > 0");
    check(positive(connectTimeout), "connectTimeout must be > 0");
    check(maxConcurrentCalls >= 1, "maxConcurrentCalls must be >= 1");
    check(!bulkheadMaxWait.isNegative(), "bulkheadMaxWait must be >= 0");
    check(!rateLimiterMaxWait.isNegative(), "rateLimiterMaxWait must be >= 0");
    check(blockPause.compareTo(Duration.ofMinutes(1)) >= 0, "blockPause must be >= 1 minute");
  }

  private static void check(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }

  private static boolean positive(Duration d) {
    return d.compareTo(Duration.ZERO) > 0;
  }

  /**
   * {@code maxAttempts} counts the first call; the wait grows by {@code multiplier} with jitter.
   */
  public record Retry(
      @DefaultValue("4") int maxAttempts,
      @DefaultValue("1s") Duration initialBackoff,
      @DefaultValue("2") double multiplier,
      @DefaultValue("0.5") double jitter) {

    public Retry {
      check(maxAttempts >= 1, "maxAttempts must be >= 1");
      check(positive(initialBackoff), "initialBackoff must be > 0");
      check(multiplier >= 1, "multiplier must be >= 1");
      check(jitter >= 0, "jitter must be >= 0");
      check(jitter < 1, "jitter must be < 1");
    }
  }

  public record CircuitBreaker(
      @DefaultValue("20") int windowSize,
      @DefaultValue("50") float failureRatePercent,
      @DefaultValue("60s") Duration openWait,
      @DefaultValue("10s") Duration slowCallThreshold) {

    public CircuitBreaker {
      check(windowSize >= 1, "windowSize must be >= 1");
      check(failureRatePercent > 0, "failureRatePercent must be > 0");
      check(failureRatePercent <= 100, "failureRatePercent must be <= 100");
      check(positive(openWait), "openWait must be > 0");
      check(positive(slowCallThreshold), "slowCallThreshold must be > 0");
    }
  }
}
