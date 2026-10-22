package br.dev.miguelsanzbr.radar.search.adapter.config;

import br.dev.miguelsanzbr.radar.search.adapter.out.metrics.MicrometerProjectionMetrics;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import br.dev.miguelsanzbr.radar.search.application.ProjectionMetrics;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the projector of the search context; the application layer has no Spring types. */
@Configuration(proxyBeanMethods = false)
class SearchProjectorConfiguration {

  @Bean
  ProjectionMetrics projectionMetrics(MeterRegistry registry) {
    return new MicrometerProjectionMetrics(registry);
  }

  @Bean
  ProcurementProjector procurementProjector(
      ProjectionStore store, ProjectionMetrics metrics, Clock clock) {
    return new ProcurementProjector(store, metrics, clock);
  }
}
