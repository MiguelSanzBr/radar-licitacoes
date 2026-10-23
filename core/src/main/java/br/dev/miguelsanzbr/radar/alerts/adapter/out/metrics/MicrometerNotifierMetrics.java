package br.dev.miguelsanzbr.radar.alerts.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code alert_delivery_latency_seconds}: match to SMTP accepted, the "Alerta" SLO of 60 s
 * (ADR-0025), with a bucket exactly at the target. {@code alert_mail_failures_total{kind}}: refused
 * mails. {@code alert_stale_events_total}: alert events ignored for their age. Every series exists
 * from the start so a dashboard shows zero instead of nothing.
 */
@Component
class MicrometerNotifierMetrics implements NotifierMetrics {

  private final Timer latency;
  private final Map<Kind, Counter> failures = new EnumMap<>(Kind.class);
  private final Counter stale;

  MicrometerNotifierMetrics(MeterRegistry registry) {
    this.latency =
        Timer.builder("alert_delivery_latency")
            .description("Time from the match of an alert until the mail server accepted it")
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofSeconds(60))
            .maximumExpectedValue(Duration.ofMinutes(5))
            .register(registry);
    Arrays.stream(Kind.values())
        .forEach(
            kind ->
                failures.put(
                    kind,
                    Counter.builder("alert_mail_failures_total")
                        .description("Mails the server did not accept, per refused try")
                        .tag("kind", kind.tag())
                        .register(registry)));
    this.stale =
        Counter.builder("alert_stale_events_total")
            .description("Alert events ignored because they were older than the maximum age")
            .register(registry);
  }

  @Override
  public void delivered(Duration elapsed) {
    latency.record(elapsed);
  }

  @Override
  public void mailFailed(Kind kind) {
    failures.get(kind).increment();
  }

  @Override
  public void staleSkipped() {
    stale.increment();
  }
}
