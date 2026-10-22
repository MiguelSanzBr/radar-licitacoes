package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Active when {@code radar.outbox.publisher=kafka} (the default). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "radar.outbox.publisher",
    havingValue = "kafka",
    matchIfMissing = true)
@EnableConfigurationProperties(KafkaPublisherProperties.class)
class KafkaOutboxConfiguration {

  @Bean
  KafkaEventPublisher kafkaEventPublisher(
      KafkaTemplate<String, String> template, KafkaPublisherProperties properties) {
    return new KafkaEventPublisher(template, properties);
  }

  @Bean
  NewTopic procurementTopic(KafkaPublisherProperties properties) {
    // why: replay and rebuilds read the procurement events from offset 0
    return topic(properties.topicFor("Procurement"), 12, properties)
        .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG, "-1"));
  }

  @Bean
  NewTopic procurementDlqTopic(KafkaPublisherProperties properties) {
    return topic(properties.topicFor("Procurement") + ".dlq", 12, properties);
  }

  @Bean
  NewTopic alertTopic(KafkaPublisherProperties properties) {
    return topic(properties.topicFor("Alert"), 6, properties);
  }

  @Bean
  NewTopic alertDlqTopic(KafkaPublisherProperties properties) {
    return topic(properties.topicFor("Alert") + ".dlq", 6, properties);
  }

  private static NewTopic topic(String name, int partitions, KafkaPublisherProperties properties) {
    return new NewTopic(name, partitions, (short) properties.replicationFactor());
  }
}
