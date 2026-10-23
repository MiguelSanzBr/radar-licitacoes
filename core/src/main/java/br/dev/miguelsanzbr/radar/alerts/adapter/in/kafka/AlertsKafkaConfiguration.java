package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
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

  @Bean
  ConcurrentKafkaListenerContainerFactory<Object, Object> alertsKafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory,
      DeadLetters deadLetters,
      @Value("${radar.alerts.matcher.retry-interval:1s}") Duration retryInterval,
      @Value("${radar.alerts.matcher.max-retries:3}") long maxRetries,
      @Value("${radar.alerts.matcher.outage-retry-interval:5s}") Duration outageRetryInterval) {
    var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
    configurer.configure(factory, consumerFactory);
    factory.setCommonErrorHandler(
        errorHandler(
            deadLetters, retryInterval.toMillis(), maxRetries, outageRetryInterval.toMillis()));
    return factory;
  }

  /**
   * Poison (a message that can never be read) goes to the dead-letter topic at once. An outage of
   * the database is retried forever, so it never dead-letters a record. Anything else, data the
   * database rejects included, gets {@code maxRetries} retries, {@code retryMillis} apart, and then
   * goes to the dead letters.
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
    return DeadLetters.isOutage(failure)
        ? new FixedBackOff(outageMillis, FixedBackOff.UNLIMITED_ATTEMPTS)
        : new FixedBackOff(retryMillis, maxRetries);
  }
}
