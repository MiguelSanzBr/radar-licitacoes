package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.TransactionException;
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

  private static final Logger LOG = LoggerFactory.getLogger(SearchKafkaConfiguration.class);

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> searchKafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
    factory.setCommonErrorHandler(errorHandler(SearchKafkaConfiguration::logAndSkip));
    return factory;
  }

  @Bean
  ProcurementEventsListener procurementEventsListener(
      ProcurementProjector projector, TraceparentExtractor traces) {
    return new ProcurementEventsListener(projector, new EnvelopeParser(), traces);
  }

  /**
   * Poison (a message that can never parse or validate) is skipped at once; a database or
   * transaction failure is retried forever, so an outage never skips a record. Anything else gets a
   * few quick retries. Skipped records are only logged until the dead-letter topic arrives (PR
   * 4.3).
   */
  static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.setBackOffFunction(SearchKafkaConfiguration::backOffFor);
    handler.addNotRetryableExceptions(
        JacksonException.class, NullPointerException.class, IllegalArgumentException.class);
    return handler;
  }

  static BackOff backOffFor(ConsumerRecord<?, ?> record, Exception failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof DataAccessException || t instanceof TransactionException) {
        return new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS);
      }
    }
    return new FixedBackOff(1000L, 3L);
  }

  static void logAndSkip(ConsumerRecord<?, ?> record, Exception failure) {
    LOG.error(
        "skipping record {}-{}@{}", record.topic(), record.partition(), record.offset(), failure);
  }
}
