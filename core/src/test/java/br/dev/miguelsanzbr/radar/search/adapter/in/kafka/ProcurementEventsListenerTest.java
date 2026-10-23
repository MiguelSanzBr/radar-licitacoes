package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.TRACEPARENT;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.AvroWire;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ProcurementEventsListenerTest {

  private final ProcurementProjector projector = mock(ProcurementProjector.class);
  private final Acknowledgment ack = mock(Acknowledgment.class);
  private final TraceparentExtractor traces = mock(TraceparentExtractor.class);
  private final BackpressureController backpressure = mock(BackpressureController.class);
  private final ProcurementEventsListener listener =
      new ProcurementEventsListener(projector, new EnvelopeParser(), traces, backpressure);

  private static ConsumerRecord<String, String> record(String value) {
    return new ConsumerRecord<>("radar.procurement.v1", 3, 17L, id(1), value);
  }

  @Test
  void aKnownEventIsProjectedAndOnlyThenAcknowledged() {
    var event = closed("c", id(1), 3);
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.APPLIED);

    listener.onMessage(record(envelope(event)), ack);

    var order = inOrder(projector, ack);
    order.verify(projector).project(event, 17L);
    order.verify(ack).acknowledge();
  }

  @Test
  void theTimeOfASuccessfulRecordFeedsTheBackpressureController() {
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.APPLIED);

    listener.onMessage(record(envelope(closed("c", id(1), 3))), ack);

    verify(backpressure).record(any());
  }

  @Test
  void aFailedRecordIsNotTimed() {
    when(projector.project(any(), anyLong())).thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> listener.onMessage(record(envelope(closed("c", id(1), 3))), ack))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(backpressure);
  }

  @Test
  void aDeferredEventIsAcknowledgedToo() {
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.DEFERRED);

    listener.onMessage(record(envelope(closed("c", id(1), 3))), ack);

    verify(ack).acknowledge();
  }

  @Test
  void aFailedProjectionIsNotAcknowledged() {
    when(projector.project(any(), anyLong())).thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> listener.onMessage(record(envelope(closed("c", id(1), 3))), ack))
        .isInstanceOf(IllegalStateException.class);

    verify(ack, never()).acknowledge();
  }

  @Test
  void anUnknownEventTypeIsAcknowledgedWithoutProjection() throws Exception {
    var envelope =
        (ObjectNode) JsonMapper.builder().build().readTree(envelope(closed("c", id(1), 3)));
    envelope.put("eventType", "AlertMatched");

    listener.onMessage(record(envelope.toString()), ack);

    verify(projector, never()).project(any(ProcurementEvent.class), anyLong());
    verify(ack).acknowledge();
    verifyNoInteractions(backpressure);
  }

  @Test
  void malformedJsonThrowsAndIsNotAcknowledged() {
    assertThatThrownBy(() -> listener.onMessage(record("{broken"), ack))
        .isInstanceOf(RuntimeException.class);

    verify(ack, never()).acknowledge();
  }

  @Test
  void theKafkaHeaderTraceWinsOverTheEnvelopeOne() {
    var header = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    var record = record(envelope(closed("c", id(1), 3)));
    record.headers().add("traceparent", header.getBytes(StandardCharsets.UTF_8));
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.APPLIED);

    listener.onMessage(record, ack);

    verify(traces).continueFrom(eq(header), anyString());
  }

  @Test
  void withoutTheHeaderTheEnvelopeTraceIsUsed() {
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.APPLIED);

    listener.onMessage(record(envelope(closed("c", id(1), 3))), ack);

    verify(traces).continueFrom(eq(TRACEPARENT), anyString());
  }

  @Test
  void anAvroRecordIsProjectedAndAcknowledgedLikeItsJsonTwin() {
    var event = closed("c", id(1), 3);
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.APPLIED);

    listener.onMessage(record(new AvroWire().roundTrip(envelope(event))), ack);

    var order = inOrder(projector, ack);
    order.verify(projector).project(event, 17L);
    order.verify(ack).acknowledge();
  }

  @Test
  void anAvroRecordKeepsTheTraceOfItsEnvelope() {
    when(projector.project(any(), anyLong())).thenReturn(ProjectionOutcome.APPLIED);

    listener.onMessage(record(new AvroWire().roundTrip(envelope(closed("c", id(1), 3)))), ack);

    verify(traces).continueFrom(eq(TRACEPARENT), anyString());
  }

  @Test
  void anAvroRecordTheDeserializerCouldNotDecodeIsPoisonAndIsNotAcknowledged() {
    assertThatThrownBy(() -> listener.onMessage(record("avro-undecodable:AAAAAQ=="), ack))
        .isInstanceOf(JacksonException.class);

    verify(ack, never()).acknowledge();
  }

  @Test
  void aRecordLeftMarkedForARegistryRetryIsNotAcknowledgedNorDeadLettered() {
    assertThatThrownBy(
            () ->
                listener.onMessage(
                    record(
                        br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException.MARKER
                            + "AAAA"),
                    ack))
        .isInstanceOf(br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException.class);

    verify(ack, never()).acknowledge();
    verifyNoInteractions(projector);
  }
}
