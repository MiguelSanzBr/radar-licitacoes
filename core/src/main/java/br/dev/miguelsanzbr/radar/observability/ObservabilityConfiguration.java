package br.dev.miguelsanzbr.radar.observability;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Tracing beans fall back to the no-op tracer so the app also starts with tracing turned off. */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 5)
  CorrelationIdFilter correlationIdFilter(UuidV7 ids) {
    return new CorrelationIdFilter(ids);
  }

  @Bean
  TraceparentInjector traceparentInjector(
      ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new TraceparentInjector(
        tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
  }

  @Bean
  TraceparentExtractor traceparentExtractor(
      ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new TraceparentExtractor(
        tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
  }
}
