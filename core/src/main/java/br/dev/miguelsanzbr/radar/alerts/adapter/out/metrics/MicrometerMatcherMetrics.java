package br.dev.miguelsanzbr.radar.alerts.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.alerts.application.port.MatcherMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@code alerts_matched_total}: new alerts; {@code alerts_match_duration}: time per event. */
@Component
class MicrometerMatcherMetrics implements MatcherMetrics {

  private final Counter matched;
  private final Timer duration;

  MicrometerMatcherMetrics(MeterRegistry registry) {
    this.matched =
        Counter.builder("alerts_matched_total")
            .description("Alerts created by the matcher, one per new saved search and procurement")
            .register(registry);
    this.duration =
        Timer.builder("alerts_match_duration")
            .description("Time to match one procurement event against the saved searches")
            .publishPercentileHistogram()
            .register(registry);
  }

  @Override
  public <T> T timed(Supplier<T> work) {
    return duration.record(work);
  }

  @Override
  public void matched(int alerts) {
    matched.increment(alerts);
  }
}
