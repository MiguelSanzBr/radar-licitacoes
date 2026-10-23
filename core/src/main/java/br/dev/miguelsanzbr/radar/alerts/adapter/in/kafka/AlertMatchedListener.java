package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.alerts.application.QueueDigestAlertUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SendInstantAlertUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
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
 * Notifier input: an INSTANT alert is mailed now, a DAILY one is kept for the digest. Any failure
 * (an unreadable message, a mail server that refuses) propagates to the container's error handler,
 * so the offset is committed only after the work of the event, or after it went to the dead-letter
 * topic.
 */
@Component
@ConditionalOnProperty(
    name = "radar.alerts.notifier.enabled",
    havingValue = "true",
    matchIfMissing = true)
class AlertMatchedListener {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SendInstantAlertUseCase instant;
  private final QueueDigestAlertUseCase digest;
  private final TraceparentExtractor traces;
  private final NotifierMetrics metrics;
  private final Clock clock;
  private final Duration maxEventAge;

  AlertMatchedListener(
      SendInstantAlertUseCase instant,
      QueueDigestAlertUseCase digest,
      TraceparentExtractor traces,
      NotifierMetrics metrics,
      Clock clock,
      @Value("${radar.alerts.max-event-age:P1D}") Duration maxEventAge) {
    this.instant = instant;
    this.digest = digest;
    this.traces = traces;
    this.metrics = metrics;
    this.clock = clock;
    this.maxEventAge = maxEventAge;
  }

  // why: earliest, so an alert published before the group first joined is not lost; the delivery
  // row (sent time) keeps a replay from mailing twice
  @KafkaListener(
      id = "alerts-notifier",
      topics = "${radar.outbox.topics.Alert}",
      containerFactory = "notifierKafkaListenerContainerFactory",
      properties = "auto.offset.reset=earliest")
  public void onMessage(ConsumerRecord<String, String> record) {
    var event = parse(record.value());
    // why: a backlog read after an outage or a replay must not turn old matches into fresh mail
    if (event.occurredAt().isBefore(clock.instant().minus(maxEventAge))) {
      metrics.staleSkipped();
      return;
    }
    var alert = event.alert();
    // why: the producer's own Kafka header wins; the envelope field is the fallback for records
    // sent without one
    var header = record.headers().lastHeader("traceparent");
    var traceparent =
        header == null ? event.traceparent() : new String(header.value(), StandardCharsets.UTF_8);
    try (var ignored = traces.continueFrom(traceparent, "notify alert")) {
      if (alert.frequency() == Frequency.INSTANT) {
        instant.send(event.eventId(), alert);
      } else {
        digest.queue(event.eventId(), alert);
      }
    }
  }

  private static AlertEnvelope parse(String message) {
    try {
      return JSON.readValue(message, AlertEnvelope.class);
    } catch (JacksonException e) {
      throw new MalformedEventException("unreadable alert event", e);
    }
  }
}
