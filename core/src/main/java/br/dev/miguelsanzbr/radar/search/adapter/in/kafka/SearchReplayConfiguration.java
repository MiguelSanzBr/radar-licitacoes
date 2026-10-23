package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.search.application.EventLog;
import br.dev.miguelsanzbr.radar.search.application.LiveProjection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;

/** The Kafka side of the replay. Always on: it works with the live projector switched off too. */
@Configuration(proxyBeanMethods = false)
class SearchReplayConfiguration {

  /** A replay that reads nothing for this long is stuck, not slow. */
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(2);

  @Bean
  LiveProjection liveProjection(KafkaListenerEndpointRegistry registry) {
    return new KafkaLiveProjection(registry);
  }

  @Bean
  EventLog procurementEventLog(
      ConsumerFactory<String, String> consumerFactory,
      @Value("${radar.outbox.topics.Procurement}") String topic,
      Clock clock) {
    return new TopicReplay(consumerFactory, new EnvelopeParser(), topic, clock, IDLE_TIMEOUT);
  }
}
