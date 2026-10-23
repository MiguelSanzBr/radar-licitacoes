package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * The listener container factory of the notifier. A mail failure is retried {@code maxRetries}
 * times with the wait doubling (1 s, 2 s, 4 s), then the record goes to {@code radar.alert.v1.dlq}
 * with the standard dead-letter headers. This stays local to the notifier; the policy for every
 * listener arrives with PR 4.3.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "radar.alerts.notifier.enabled",
    havingValue = "true",
    matchIfMissing = true)
class NotifierKafkaConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<Object, Object> notifierKafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory,
      KafkaTemplate<String, String> template,
      @Value("${radar.alerts.notifier.retry-interval:1s}") Duration retryInterval,
      @Value("${radar.alerts.notifier.max-retries:3}") int maxRetries,
      @Value("${radar.alerts.notifier.outage-retry-interval:5s}") Duration outageRetryInterval) {
    var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
    configurer.configure(factory, consumerFactory);
    factory.setCommonErrorHandler(
        errorHandler(
            new DeadLetterPublishingRecoverer(
                template, NotifierKafkaConfiguration::deadLetterTopic),
            retryInterval.toMillis(),
            maxRetries,
            outageRetryInterval.toMillis()));
    return factory;
  }

  /**
   * Poison (a message that can never be read) goes to the dead-letter topic at once. A database
   * outage is retried forever, as in the matcher, so it never sends a mail to the dead letters.
   */
  static DefaultErrorHandler errorHandler(
      ConsumerRecordRecoverer recoverer, long retryMillis, int maxRetries, long outageMillis) {
    var handler = new DefaultErrorHandler(recoverer, backOff(retryMillis, maxRetries));
    handler.setBackOffFunction(
        (record, failure) -> backOffFor(failure, retryMillis, maxRetries, outageMillis));
    handler.addNotRetryableExceptions(
        MalformedEventException.class,
        JacksonException.class,
        NullPointerException.class,
        IllegalArgumentException.class);
    return handler;
  }

  static BackOff backOff(long retryMillis, int maxRetries) {
    var backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
    backOff.setInitialInterval(retryMillis);
    backOff.setMultiplier(2);
    return backOff;
  }

  static BackOff backOffFor(
      Exception failure, long retryMillis, int maxRetries, long outageMillis) {
    return AlertsKafkaConfiguration.databaseFailure(failure)
        ? new FixedBackOff(outageMillis, FixedBackOff.UNLIMITED_ATTEMPTS)
        : backOff(retryMillis, maxRetries);
  }

  /** Negative partition: the producer picks it from the key, so a subscriber stays in order. */
  static TopicPartition deadLetterTopic(ConsumerRecord<?, ?> record, Exception failure) {
    return new TopicPartition(record.topic() + ".dlq", -1);
  }
}
