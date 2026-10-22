package br.dev.miguelsanzbr.radar.search.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerProjectionMetricsTest {

  @Test
  void lagIsRecordedInTheProjectionLagTimer() {
    var registry = new SimpleMeterRegistry();
    var metrics = new MicrometerProjectionMetrics(registry);

    metrics.lag(Duration.ofSeconds(3));
    metrics.lag(Duration.ofSeconds(1));

    var timer = registry.get("projection_lag_seconds").timer();
    assertThat(timer.count()).isEqualTo(2);
    assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(4.0);
  }

  @Test
  void deferredEventsAreCountedFromZero() {
    var registry = new SimpleMeterRegistry();
    var metrics = new MicrometerProjectionMetrics(registry);
    assertThat(registry.get("projection_deferred_total").counter().count()).isZero();

    metrics.deferred();
    metrics.deferred();

    assertThat(registry.get("projection_deferred_total").counter().count()).isEqualTo(2.0);
  }
}
