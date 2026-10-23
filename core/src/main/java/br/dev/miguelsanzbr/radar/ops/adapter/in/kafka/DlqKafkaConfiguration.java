package br.dev.miguelsanzbr.radar.ops.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * The recorder's container factory. An outage of the database is retried forever, so no dead
 * message goes unrecorded. A message the database rejects (a NUL character in the text, say) is
 * retried a few times and then skipped with a log: it stays in the dead-letter topic, which is
 * never deleted by the recorder.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "radar.ops.dlq-recorder.enabled",
    havingValue = "true",
    matchIfMissing = true)
class DlqKafkaConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(DlqKafkaConfiguration.class);

  @Bean
  ConcurrentKafkaListenerContainerFactory<Object, Object> opsKafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory,
      MeterRegistry registry) {
    var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
    configurer.configure(factory, consumerFactory);
    factory.setCommonErrorHandler(errorHandler(skipper(registry)));
    return factory;
  }

  static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.setBackOffFunction(DlqKafkaConfiguration::backOffFor);
    return handler;
  }

  static BackOff backOffFor(ConsumerRecord<?, ?> record, Exception failure) {
    return DeadLetters.isOutage(failure)
        ? new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS)
        : new FixedBackOff(1000L, 3L);
  }

  /** Logs and counts the record the recorder gives up on, so the skip is not silent. */
  static ConsumerRecordRecoverer skipper(MeterRegistry registry) {
    var skipped =
        Counter.builder("dlq_recorder_skipped_total")
            .description("Dead letters the recorder gave up on; they stay in the dead-letter topic")
            .register(registry);
    return (record, failure) -> {
      skipped.increment();
      LOG.error(
          "dead message {}-{}@{} was not recorded",
          record.topic(),
          record.partition(),
          record.offset(),
          failure);
    };
  }
}
