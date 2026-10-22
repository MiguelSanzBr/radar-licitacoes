package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code topics} maps an aggregate type to its topic; {@code publishTimeout} bounds the wait for
 * the broker ack of one message.
 */
@ConfigurationProperties("radar.outbox")
public record KafkaPublisherProperties(
    Map<String, String> topics,
    @DefaultValue("10s") Duration publishTimeout,
    @DefaultValue("1") int replicationFactor) {

  public KafkaPublisherProperties {
    topics = Map.copyOf(topics);
  }

  /** The configured topic of an aggregate type; a missing mapping fails the startup. */
  public String topicFor(String aggregateType) {
    return Objects.requireNonNull(
        topics.get(aggregateType), "radar.outbox.topics." + aggregateType);
  }
}
