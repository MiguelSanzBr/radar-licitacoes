package br.dev.miguelsanzbr.radar.ops.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.domain.DeadMessage;
import br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Copies every record of the dead-letter topics into {@code ops.dlq_message} so the operator can
 * list and reprocess them. A redelivery stores nothing twice.
 */
@Component
@ConditionalOnProperty(
    name = "radar.ops.dlq-recorder.enabled",
    havingValue = "true",
    matchIfMissing = true)
class DlqRecorderListener {

  private final DlqMessages messages;
  private final Clock clock;

  DlqRecorderListener(DlqMessages messages, Clock clock) {
    this.messages = messages;
    this.clock = clock;
  }

  @KafkaListener(
      topics = {"${radar.outbox.topics.Procurement}.dlq", "${radar.outbox.topics.Alert}.dlq"},
      groupId = "${radar.ops.dlq-recorder.group:ops-dlq-recorder}",
      containerFactory = "opsKafkaListenerContainerFactory",
      properties = "auto.offset.reset=earliest")
  void onMessage(ConsumerRecord<String, String> record) {
    RegistryUnavailableException.rejectIfMarked(record.value());
    var headers = new LinkedHashMap<String, String>();
    for (var header : record.headers()) {
      if (header.value() != null) {
        headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
      }
    }
    // a record without a readable origin is keyed on its own position in the dead-letter topic
    var origin = origin(record, headers);
    messages.record(
        new DeadMessage(
            record.topic(),
            record.partition(),
            record.offset(),
            record.key(),
            record.value(),
            headers,
            headers.get(DeadLetters.ERROR_CLASS),
            headers.get(DeadLetters.ERROR_MESSAGE),
            origin.topic(),
            origin.partition(),
            origin.offset(),
            headers.getOrDefault(DeadLetters.CONSUMER_GROUP, ""),
            clock.instant()));
  }

  private record Origin(String topic, int partition, long offset) {}

  private static Origin origin(ConsumerRecord<?, ?> record, Map<String, String> headers) {
    var topic = headers.get(DeadLetters.ORIGINAL_TOPIC);
    var partition = headers.get(DeadLetters.ORIGINAL_PARTITION);
    var offset = headers.get(DeadLetters.ORIGINAL_OFFSET);
    if (topic != null && partition != null && offset != null) {
      try {
        return new Origin(topic, Integer.parseInt(partition), Long.parseLong(offset));
      } catch (NumberFormatException e) {
        // falls through to the record's own position
      }
    }
    return new Origin(record.topic(), record.partition(), record.offset());
  }
}
