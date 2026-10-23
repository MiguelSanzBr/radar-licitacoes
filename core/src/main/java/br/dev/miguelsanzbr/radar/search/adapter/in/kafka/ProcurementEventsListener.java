package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes the procurement topic for the search read model. The record is acknowledged only after
 * the projection committed. A failure leaves it unacknowledged and goes to the container's error
 * handler (bounded retries, then logged and skipped until the dead-letter topic arrives).
 */
class ProcurementEventsListener {

  /** Lets the replay find the container and stop it. */
  static final String ID = "search-projector-listener";

  private static final Logger LOG = LoggerFactory.getLogger(ProcurementEventsListener.class);

  private final ProcurementProjector projector;
  private final EnvelopeParser parser;
  private final TraceparentExtractor traces;

  ProcurementEventsListener(
      ProcurementProjector projector, EnvelopeParser parser, TraceparentExtractor traces) {
    this.projector = projector;
    this.parser = parser;
    this.traces = traces;
  }

  @KafkaListener(
      id = ID,
      topics = "${radar.outbox.topics.Procurement}",
      groupId = "${radar.search.projector.group:search-projector}",
      containerFactory = "searchKafkaListenerContainerFactory",
      properties = "auto.offset.reset=earliest")
  void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
    RegistryUnavailableException.rejectIfMarked(record.value());
    var parsed = parser.parse(record.value());
    if (parsed.isPresent()) {
      var event = parsed.get().event();
      try (var ignored = traces.continueFrom(traceparent(record, parsed.get()), "search project")) {
        if (projector.project(event, record.offset()) == ProjectionOutcome.DEFERRED) {
          LOG.warn(
              "event {} of {} arrived before its procurement was projected and was not applied",
              event.eventId(),
              event.aggregateId());
        }
      }
    }
    ack.acknowledge();
  }

  /** The producer's Kafka header first, the envelope field when the record has none. */
  private static String traceparent(
      ConsumerRecord<String, String> record, EnvelopeParser.Parsed parsed) {
    var header = record.headers().lastHeader("traceparent");
    return header == null
        ? parsed.traceparent()
        : new String(header.value(), StandardCharsets.UTF_8);
  }
}
