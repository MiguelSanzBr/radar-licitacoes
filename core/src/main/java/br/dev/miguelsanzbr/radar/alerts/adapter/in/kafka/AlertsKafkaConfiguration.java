package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.TransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * The listener container factory of the matcher, kept apart from Boot's default one so other
 * contexts can consume the same topic with their own settings.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "radar.alerts.matcher.enabled",
    havingValue = "true",
    matchIfMissing = true)
class AlertsKafkaConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(AlertsKafkaConfiguration.class);

  @Bean
  ConcurrentKafkaListenerContainerFactory<Object, Object> alertsKafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory,
      @Value("${radar.alerts.matcher.retry-interval:1s}") Duration retryInterval,
      @Value("${radar.alerts.matcher.max-retries:3}") long maxRetries,
      @Value("${radar.alerts.matcher.outage-retry-interval:5s}") Duration outageRetryInterval) {
    var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
    configurer.configure(factory, consumerFactory);
    factory.setCommonErrorHandler(
        errorHandler(
            AlertsKafkaConfiguration::logAndSkip,
            retryInterval.toMillis(),
            maxRetries,
            outageRetryInterval.toMillis()));
    return factory;
  }

  /**
   * Poison (a message that can never be read) is skipped at once. A database or transaction failure
   * is retried forever, so an outage never skips a record. Anything else gets {@code maxRetries}
   * retries, {@code retryMillis} apart. Skipped records are only logged until the dead-letter topic
   * arrives (PR 4.3).
   */
  static DefaultErrorHandler errorHandler(
      ConsumerRecordRecoverer recoverer, long retryMillis, long maxRetries, long outageMillis) {
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryMillis, maxRetries));
    handler.setBackOffFunction(
        (record, failure) -> backOffFor(failure, retryMillis, maxRetries, outageMillis));
    handler.addNotRetryableExceptions(
        MalformedEventException.class,
        JacksonException.class,
        NullPointerException.class,
        IllegalArgumentException.class);
    return handler;
  }

  static BackOff backOffFor(
      Exception failure, long retryMillis, long maxRetries, long outageMillis) {
    return databaseFailure(failure)
        ? new FixedBackOff(outageMillis, FixedBackOff.UNLIMITED_ATTEMPTS)
        : new FixedBackOff(retryMillis, maxRetries);
  }

  /** A database or transaction failure anywhere in the cause chain: an outage, not bad data. */
  static boolean databaseFailure(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof DataAccessException || t instanceof TransactionException) {
        return true;
      }
    }
    return false;
  }

  static void logAndSkip(ConsumerRecord<?, ?> record, Exception failure) {
    LOG.error(
        "skipping record {}-{}@{}", record.topic(), record.partition(), record.offset(), failure);
  }
}
