package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import br.dev.miguelsanzbr.radar.outbox.BrokerUnavailableException;
import br.dev.miguelsanzbr.radar.outbox.EventPublisher;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import br.dev.miguelsanzbr.radar.outbox.PublishException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the event envelope to the topic of its aggregate type, keyed by aggregate id so one
 * aggregate stays in one partition. Waits for the ack; every failure is a {@link PublishException},
 * a {@link BrokerUnavailableException} when the broker as a whole did not answer.
 */
public class KafkaEventPublisher implements EventPublisher {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Function<ProducerRecord<String, String>, Future<?>> sender;
  private final KafkaPublisherProperties properties;

  public KafkaEventPublisher(
      KafkaTemplate<String, String> template, KafkaPublisherProperties properties) {
    this.sender = record -> template.send(record);
    this.properties = properties;
  }

  @Override
  public void publish(OutboxMessage message) throws PublishException {
    var topic = properties.topics().get(message.aggregateType());
    if (topic == null) {
      throw new PublishException(
          "no topic configured for aggregate type " + message.aggregateType());
    }
    try {
      var record = new ProducerRecord<>(topic, message.aggregateId(), envelope(message));
      header(record, "eventId", message.eventId().toString());
      header(record, "eventType", message.eventType());
      header(record, "schemaVersion", Integer.toString(message.schemaVersion()));
      var traceparent = message.headers().get("traceparent");
      if (traceparent != null) {
        header(record, "traceparent", traceparent.toString());
      }
      sender.apply(record).get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublishException("interrupted while publishing " + message.aggregateId(), e);
    } catch (TimeoutException e) {
      throw new BrokerUnavailableException("no ack for " + message.aggregateId(), e);
    } catch (ExecutionException | RuntimeException e) {
      var text = "kafka publish failed for " + message.aggregateId() + ": " + e.getMessage();
      if (e.getCause() instanceof RetriableException) {
        throw new BrokerUnavailableException(text, e);
      }
      throw new PublishException(text, e);
    }
  }

  /** The row stores the payload; the rest of the envelope comes from its columns and headers. */
  private static String envelope(OutboxMessage message) {
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("eventId", message.eventId().toString());
    envelope.put("eventType", message.eventType());
    envelope.put("schemaVersion", message.schemaVersion());
    envelope.put("aggregateType", message.aggregateType());
    envelope.put("aggregateId", message.aggregateId());
    envelope.put("aggregateVersion", message.headers().get("aggregateVersion"));
    // why: the row's created_at is when it was appended; the header holds the domain event time
    envelope.put(
        "occurredAt", message.headers().getOrDefault("occurredAt", message.createdAt().toString()));
    envelope.put("traceparent", message.headers().get("traceparent"));
    envelope.put("payload", JSON.readTree(message.payload()));
    return JSON.writeValueAsString(envelope);
  }

  private static void header(ProducerRecord<String, String> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
