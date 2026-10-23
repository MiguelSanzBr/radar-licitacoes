package br.dev.miguelsanzbr.radar.identity.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.identity.application.RateLimiterUnavailableException;
import br.dev.miguelsanzbr.radar.identity.application.port.RateLimitMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code rate_limit_fail_open_total}: public reads let through unmetered because Redis could not be
 * reached (ADR-0027). The series exists from the start; the WARN is logged once a minute so an
 * outage does not flood the log at request rate.
 */
public class MicrometerRateLimitMetrics implements RateLimitMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(MicrometerRateLimitMetrics.class);
  private static final long WARN_EVERY_SECONDS = 60;

  private final Counter failOpen;
  private final Clock clock;
  private final AtomicReference<Instant> lastWarning = new AtomicReference<>();

  public MicrometerRateLimitMetrics(MeterRegistry registry, Clock clock) {
    this.failOpen =
        Counter.builder("rate_limit_fail_open_total")
            .description("Public reads let through unmetered because the rate limiter was down")
            .register(registry);
    this.clock = clock;
  }

  @Override
  public void failedOpen(RateLimiterUnavailableException cause) {
    failOpen.increment();
    var now = clock.instant();
    var previous = lastWarning.getAndUpdate(last -> due(last, now) ? now : last);
    if (due(previous, now)) {
      LOG.warn(
          "Rate limiter unavailable, public reads pass unmetered (fail-open): {}",
          // the text, not the Throwable: a Throwable argument would be logged as a stack trace
          cause.getCause().toString());
    }
  }

  private static boolean due(Instant last, Instant now) {
    return last == null || !now.isBefore(last.plusSeconds(WARN_EVERY_SECONDS));
  }
}
