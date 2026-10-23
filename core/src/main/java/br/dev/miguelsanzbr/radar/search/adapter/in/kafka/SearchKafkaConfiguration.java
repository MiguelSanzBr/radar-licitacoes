package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
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
    factory.setCommonErrorHandler(errorHandler(deadLetters));
    return factory;
  }

  @Bean
  ProcurementEventsListener procurementEventsListener(
      ProcurementProjector projector, TraceparentExtractor traces) {
    return new ProcurementEventsListener(projector, new EnvelopeParser(), traces);
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
