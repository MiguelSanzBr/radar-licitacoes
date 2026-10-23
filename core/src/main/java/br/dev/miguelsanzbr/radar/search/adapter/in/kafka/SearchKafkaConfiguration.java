package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * The projector consumer, on its own container factory so its acknowledgement mode and error
 * handling do not leak into other listeners. Switched off with {@code
 * radar.search.projector.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "radar.search.projector.enabled",
    havingValue = "true",
    matchIfMissing = true)
class SearchKafkaConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> searchKafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory, DeadLetters deadLetters) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
    // why: a pause must stop the batch in hand after the current record, not after 500 records
    factory.getContainerProperties().setPauseImmediate(true);
    factory.setCommonErrorHandler(errorHandler(deadLetters));
    return factory;
  }

  @Bean
  ProcurementEventsListener procurementEventsListener(
      ProcurementProjector projector,
      TraceparentExtractor traces,
      BackpressureController backpressure) {
    return new ProcurementEventsListener(projector, new EnvelopeParser(), traces, backpressure);
  }

  /** The gauge {@code search_projector_paused} is 1 while the controller holds the container. */
  @Bean
  BackpressureController backpressureController(
      KafkaListenerEndpointRegistry registry,
      Clock clock,
      MeterRegistry meters,
      @Value("${radar.search.projector.backpressure.slow-after:250ms}") Duration slowAfter,
      @Value("${radar.search.projector.backpressure.window:20}") int window,
      @Value("${radar.search.projector.backpressure.pause-for:10s}") Duration pauseFor) {
    var timer =
        Timer.builder("search_projector_record_seconds")
            .description("Time the projector takes per record, the input of the backpressure")
            .publishPercentileHistogram()
            .register(meters);
    var controller =
        new BackpressureController(registry, clock, slowAfter, window, pauseFor, timer);
    Gauge.builder("search_projector_paused", controller, c -> c.paused() ? 1 : 0)
        .description("1 while the projector container is paused by backpressure")
        .register(meters);
    return controller;
  }

  /**
   * Poison (a message that can never parse or validate) goes to the dead-letter topic at once; an
   * outage of the database is retried forever, so it never dead-letters a record. Anything else,
   * data the database rejects included, gets a few quick retries and then goes to the dead letters.
   */
  static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.setBackOffFunction(SearchKafkaConfiguration::backOffFor);
    handler.addNotRetryableExceptions(
        JacksonException.class, NullPointerException.class, IllegalArgumentException.class);
    return handler;
  }

  static BackOff backOffFor(ConsumerRecord<?, ?> record, Exception failure) {
    return DeadLetters.isOutage(failure)
        ? new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)
        : new FixedBackOff(1000L, 3L);
  }
}
