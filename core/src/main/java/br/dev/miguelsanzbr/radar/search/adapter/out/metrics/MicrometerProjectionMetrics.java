package br.dev.miguelsanzbr.radar.search.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.search.application.ProjectionMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** {@code projection_lag_seconds}: how far the read side runs behind the domain events. */
public class MicrometerProjectionMetrics implements ProjectionMetrics {

  private final Timer lag;
  private final Counter deferred;

  public MicrometerProjectionMetrics(MeterRegistry registry) {
    this.lag =
        Timer.builder("projection_lag_seconds")
            .description("Time between a procurement event and its projection")
            .publishPercentileHistogram()
            .register(registry);
    this.deferred =
        Counter.builder("projection_deferred_total")
            .description("Events skipped because their procurement was not projected yet")
            .register(registry);
  }

  @Override
  public void lag(Duration lag) {
    this.lag.record(lag);
  }

  @Override
  public void deferred() {
    deferred.increment();
  }
}
