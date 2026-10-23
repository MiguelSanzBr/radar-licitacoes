package br.dev.miguelsanzbr.radar.identity.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.identity.application.RateLimiterUnavailableException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MicrometerRateLimitMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
  private final MicrometerRateLimitMetrics metrics =
      new MicrometerRateLimitMetrics(registry, clock);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(MicrometerRateLimitMetrics.class);
  private final RateLimiterUnavailableException failure =
      new RateLimiterUnavailableException(new IllegalStateException("connection refused"));

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private double failOpenCount() {
    return registry.get("rate_limit_fail_open_total").counter().count();
  }

  @Test
  void theCounterExistsFromTheStartAndCountsEveryFailOpenRequest() {
    assertThat(failOpenCount()).isZero();

    metrics.failedOpen(failure);
    metrics.failedOpen(failure);
    metrics.failedOpen(failure);

    assertThat(failOpenCount()).isEqualTo(3);
  }

  @Test
  void theWarningIsLoggedOnceAMinute() {
    metrics.failedOpen(failure);
    clock.advance(Duration.ofSeconds(59));
    metrics.failedOpen(failure);
    metrics.failedOpen(failure);

    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(logs.list.getFirst().getFormattedMessage()).contains("connection refused");

    clock.advance(Duration.ofSeconds(1));
    metrics.failedOpen(failure);
    metrics.failedOpen(failure);

    assertThat(logs.list).hasSize(2);
    assertThat(failOpenCount()).isEqualTo(5);
  }
}
