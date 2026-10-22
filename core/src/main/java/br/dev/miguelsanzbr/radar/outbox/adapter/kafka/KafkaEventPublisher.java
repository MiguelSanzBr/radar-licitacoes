package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
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
  private final TraceparentExtractor traces;

  public KafkaEventPublisher(
      KafkaTemplate<String, String> template,
      KafkaPublisherProperties properties,
      TraceparentExtractor traces) {
    this.sender = record -> template.send(record);
    this.properties = properties;
    this.traces = traces;
  }

  @Override
  public void publish(OutboxMessage message) throws PublishException {
    var topic = properties.topics().get(message.aggregateType());
    if (topic == null) {
      throw new PublishException(
          "no topic configured for aggregate type " + message.aggregateType());
    }
    var traceparent = message.headers().get("traceparent");
    // why: the relay is async, so the send joins the trace of the request that wrote the row; the
    // template's producer observation then re-stamps the header as a child of that same trace
    try (var ignored =
        traces.continueFrom(
            traceparent == null ? null : traceparent.toString(), "outbox publish")) {
      var record = new ProducerRecord<>(topic, message.aggregateId(), envelope(message));
      header(record, "eventId", message.eventId().toString());
      header(record, "eventType", message.eventType());
      header(record, "schemaVersion", Integer.toString(message.schemaVersion()));
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
      if (brokerWide(e)) {
        throw new BrokerUnavailableException(text, e);
      }
      throw new PublishException(text, e);
    }
  }

  /**
   * Broker-wide when any exception in the cause chain is retriable (timeouts, disconnects, network,
   * replicas; a full producer buffer is a timeout too). The template wraps what the producer
   * reports in a {@code KafkaProducerException}, so the top-level cause is not enough.
   */
  private static boolean brokerWide(Throwable failure) {
    for (var t = failure; t != null; t = t.getCause()) {
      if (t instanceof RetriableException) {
        return true;
      }
    }
    return false;
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
