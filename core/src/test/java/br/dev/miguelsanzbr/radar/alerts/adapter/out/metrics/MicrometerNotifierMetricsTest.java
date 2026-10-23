package br.dev.miguelsanzbr.radar.alerts.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerNotifierMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerNotifierMetrics metrics = new MicrometerNotifierMetrics(registry);

  @Test
  void deliveryLatencyIsATimerNamedForPrometheusSeconds() {
    metrics.delivered(Duration.ofMillis(2500));

    var timer = registry.get("alert_delivery_latency").timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2500.0);
  }

  @Test
  void mailFailuresAreCountedPerKindAndExistFromTheStart() {
    for (var kind : Kind.values()) {
      assertThat(
              registry.get("alert_mail_failures_total").tag("kind", kind.tag()).counter().count())
          .isZero();
    }

    metrics.mailFailed(Kind.DIGEST);
    metrics.mailFailed(Kind.DIGEST);
    metrics.mailFailed(Kind.INSTANT);

    assertThat(registry.get("alert_mail_failures_total").tag("kind", "digest").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("alert_mail_failures_total").tag("kind", "instant").counter().count())
        .isEqualTo(1);
    assertThat(
            registry.get("alert_mail_failures_total").tag("kind", "verification").counter().count())
        .isZero();
  }

  @Test
  void staleEventsAreCounted() {
    metrics.staleSkipped();

    assertThat(registry.get("alert_stale_events_total").counter().count()).isEqualTo(1);
  }
}
