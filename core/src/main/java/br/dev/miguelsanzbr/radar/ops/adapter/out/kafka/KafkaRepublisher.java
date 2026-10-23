package br.dev.miguelsanzbr.radar.ops.adapter.out.kafka;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.ops.application.port.Republisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Sends with the marker header {@code x-reprocessed-from=dlq} and waits for the broker. */
@Component
class KafkaRepublisher implements Republisher {

  private final KafkaTemplate<String, String> template;
  private final Duration timeout;

  KafkaRepublisher(
      KafkaTemplate<String, String> template,
      @Value("${radar.ops.republish-timeout:10s}") Duration timeout) {
    this.template = template;
    this.timeout = timeout;
  }

  @Override
  public void republish(String topic, String key, String value) {
    var record = new ProducerRecord<>(topic, key, value);
    record
        .headers()
        .add(
            new RecordHeader(DeadLetters.REPROCESSED_FROM, "dlq".getBytes(StandardCharsets.UTF_8)));
    try {
      template.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("could not republish to " + topic, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while republishing to " + topic, e);
    }
  }
}
