package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import br.dev.miguelsanzbr.radar.alerts.AlertEnvelopes;
import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.RecordingNotifierMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.QueueDigestAlertUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SendInstantAlertUseCase;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AlertMatchedListenerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private final SendInstantAlertUseCase instant = mock(SendInstantAlertUseCase.class);
  private final QueueDigestAlertUseCase digest = mock(QueueDigestAlertUseCase.class);
  private final List<String> traces = new ArrayList<>();
  private final List<String> closed = new ArrayList<>();
  private final RecordingNotifierMetrics metrics = new RecordingNotifierMetrics();

  private final AlertMatchedListener listener =
      new AlertMatchedListener(
          instant,
          digest,
          new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP) {
            @Override
            public Scope continueFrom(String traceparent, String name) {
              traces.add(traceparent + "|" + name);
              return () -> closed.add(name);
            }
          },
          metrics,
          Clock.fixed(Facts.NOW, ZoneOffset.UTC),
          Duration.ofDays(1));

  private static AlertMatched alert(Frequency frequency) {
    return AlertMatched.of(
        new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), frequency),
        Facts.procurement().build());
  }

  private static ConsumerRecord<String, String> record(ObjectNode envelope) {
    return new ConsumerRecord<>("radar.alert.v1", 2, 0L, "key", JSON.writeValueAsString(envelope));
  }

  @Test
  void anInstantAlertIsSentRightAway() {
    var event = UUID.randomUUID();
    var alert = alert(Frequency.INSTANT);

    listener.onMessage(record(AlertEnvelopes.node(event, alert, TRACE, Facts.NOW)));

    verify(instant).send(event, alert);
    verifyNoInteractions(digest);
  }

  @Test
  void aDailyAlertIsLeftForTheDigest() {
    var event = UUID.randomUUID();
    var alert = alert(Frequency.DAILY);

    listener.onMessage(record(AlertEnvelopes.node(event, alert, TRACE, Facts.NOW)));

    verify(digest).queue(event, alert);
    verifyNoInteractions(instant);
  }

  @Test
  void anAlertWithoutEstimatedValueOrClosingTimeIsStillRead() {
    var sparse = alert(Frequency.INSTANT);
    var node = AlertEnvelopes.node(UUID.randomUUID(), sparse, null, Facts.NOW);
    ((ObjectNode) node.get("payload")).putNull("estimatedValue").putNull("proposalClosingAt");

    listener.onMessage(record(node));

    verify(instant)
        .send(
            any(),
            ArgumentMatchers.argThat(
                a -> a.estimatedValue() == null && a.proposalClosingAt() == null));
  }

  @Test
  void theWorkRunsInsideASpanOfTheProducersTrace() {
    listener.onMessage(
        record(AlertEnvelopes.node(UUID.randomUUID(), alert(Frequency.INSTANT), TRACE, Facts.NOW)));

    assertThat(traces).containsExactly(TRACE + "|notify alert");
    assertThat(closed).containsExactly("notify alert");
  }

  @Test
  void theProducersKafkaHeaderWinsOverTheEnvelopeTraceparent() {
    var header = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
    var record =
        record(AlertEnvelopes.node(UUID.randomUUID(), alert(Frequency.DAILY), TRACE, Facts.NOW));
    record.headers().add("traceparent", header.getBytes(StandardCharsets.UTF_8));

    listener.onMessage(record);

    assertThat(traces).containsExactly(header + "|notify alert");
  }

  @Test
  void aMessageThatIsNotJsonIsMalformed() {
    assertThatThrownBy(
            () -> listener.onMessage(new ConsumerRecord<>("radar.alert.v1", 0, 0L, "k", "nope")))
        .isInstanceOf(MalformedEventException.class);
    verifyNoInteractions(instant, digest);
  }

  @Test
  void anEnvelopeMissingWhatTheNotifierNeedsIsMalformed() {
    Consumer<String> without =
        field -> {
          var node =
              AlertEnvelopes.node(UUID.randomUUID(), alert(Frequency.INSTANT), TRACE, Facts.NOW);
          if (field.startsWith("payload.")) {
            ((ObjectNode) node.get("payload")).remove(field.substring(8));
          } else {
            node.remove(field);
          }
          assertThatThrownBy(() -> listener.onMessage(record(node)))
              .as(field)
              .isInstanceOf(MalformedEventException.class);
        };

    for (var field :
        List.of(
            "eventId",
            "occurredAt",
            "payload",
            "payload.savedSearchId",
            "payload.subscriberId",
            "payload.procurementId",
            "payload.frequency",
            "payload.objectDescription",
            "payload.organName",
            "payload.detailPath")) {
      without.accept(field);
    }
    verifyNoInteractions(instant, digest);
  }

  @Test
  void anEventOlderThanTheMaximumAgeIsSkippedAndCounted() {
    var stale = Facts.NOW.minus(Duration.ofDays(1)).minusSeconds(1);

    listener.onMessage(
        record(AlertEnvelopes.node(UUID.randomUUID(), alert(Frequency.INSTANT), TRACE, stale)));
    listener.onMessage(
        record(AlertEnvelopes.node(UUID.randomUUID(), alert(Frequency.DAILY), TRACE, stale)));

    verifyNoInteractions(instant, digest);
    assertThat(metrics.stale).isEqualTo(2);
    assertThat(traces).isEmpty();
  }

  @Test
  void anEventExactlyAtTheMaximumAgeIsStillHandled() {
    var edge = Facts.NOW.minus(Duration.ofDays(1));

    listener.onMessage(
        record(AlertEnvelopes.node(UUID.randomUUID(), alert(Frequency.INSTANT), TRACE, edge)));

    verify(instant).send(any(), any());
    assertThat(metrics.stale).isZero();
  }
}
