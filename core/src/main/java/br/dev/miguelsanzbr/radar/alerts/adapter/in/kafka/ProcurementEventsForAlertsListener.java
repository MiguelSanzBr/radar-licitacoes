package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Matcher input: procurements discovered or changed in what a search looks at. Situation changes
 * and closures are acknowledged without work, since alerts never notify about them. Any failure (an
 * unreadable message, a database error) propagates to the container's error handler, so the offset
 * is committed only after the transaction of the event.
 */
@Component
@ConditionalOnProperty(
    name = "radar.alerts.matcher.enabled",
    havingValue = "true",
    matchIfMissing = true)
class ProcurementEventsForAlertsListener {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final MatchProcurementUseCase useCase;
  private final TraceparentExtractor traces;
  private final Clock clock;
  private final Counter staleSkipped;
  private final Duration maxEventAge;

  ProcurementEventsForAlertsListener(
      MatchProcurementUseCase useCase,
      TraceparentExtractor traces,
      Clock clock,
      MeterRegistry registry,
      @Value("${radar.alerts.max-event-age:P1D}") Duration maxEventAge) {
    this.useCase = useCase;
    this.traces = traces;
    this.clock = clock;
    this.staleSkipped =
        Counter.builder("alerts_stale_events_skipped_total")
            .description(
                "Procurement events the matcher skipped for being older than the maximum age")
            .register(registry);
    this.maxEventAge = maxEventAge;
  }

  // why: the group starts at the end of the topic. Replaying the whole history would alert about
  // procurements that were published long before the search existed
  @KafkaListener(
      id = "alerts-matcher",
      topics = "${radar.outbox.topics.Procurement}",
      containerFactory = "alertsKafkaListenerContainerFactory",
      properties = "auto.offset.reset=latest")
  public void onMessage(ConsumerRecord<String, String> record) {
    var event = parse(record.value());
    switch (event.eventType()) {
      case "ProcurementDiscovered" ->
          match(record, event, () -> useCase.onDiscovered(event.eventId(), event.facts()));
      case "ProcurementUpdated" ->
          match(
              record,
              event,
              () -> useCase.onUpdated(event.eventId(), event.facts(), event.changedFields()));
      default -> {}
    }
  }

  /**
   * Skips an event older than the maximum age before anything is written: a backlog read after an
   * outage or a replay must not turn old procurements into fresh alerts.
   */
  private void match(
      ConsumerRecord<String, String> record, ProcurementEnvelope event, Runnable work) {
    // an event the operator put back from the dead letters is old by definition and is wanted
    if (!DeadLetters.reprocessed(record)
        && event.occurredAt().isBefore(clock.instant().minus(maxEventAge))) {
      staleSkipped.increment();
      return;
    }
    // why: the container's observation does not restore the trace of the producer; the Kafka header
    // is the producer's own, the envelope field is the fallback for records sent without one
    var header = record.headers().lastHeader("traceparent");
    var traceparent =
        header == null ? event.traceparent() : new String(header.value(), StandardCharsets.UTF_8);
    try (var ignored = traces.continueFrom(traceparent, "match procurement")) {
      work.run();
    }
  }

  private static ProcurementEnvelope parse(String message) {
    RegistryUnavailableException.rejectIfMarked(message);
    try {
      return JSON.readValue(message, ProcurementEnvelope.class);
    } catch (JacksonException e) {
      throw new MalformedEventException("unreadable procurement event", e);
    }
  }
}
