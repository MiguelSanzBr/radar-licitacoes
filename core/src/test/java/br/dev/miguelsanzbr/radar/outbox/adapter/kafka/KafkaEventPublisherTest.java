package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.outbox.BrokerUnavailableException;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import br.dev.miguelsanzbr.radar.outbox.PublishException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.BufferExhaustedException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

class KafkaEventPublisherTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID EVENT_ID = UUID.fromString("0198a5c0-0000-7000-8000-000000000001");

  private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
  private final KafkaPublisherProperties properties =
      new KafkaPublisherProperties(
          Map.of("Procurement", "radar.procurement.v1", "Alert", "radar.alert.v1"),
          Duration.ofMillis(200),
          1);
  private final KafkaEventPublisher publisher = new KafkaEventPublisher(template, properties);

  @BeforeEach
  void acknowledge() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
  }

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  private static OutboxMessage message(String aggregateType, Map<String, Object> headers) {
    return new OutboxMessage(
        7,
        EVENT_ID,
        aggregateType,
        "PNCP-1",
        "ProcurementUpdated",
        2,
        "{\"k\":1,\"nested\":{\"a\":[1,2]}}",
        headers,
        Instant.parse("2026-10-22T12:00:00Z"),
        0);
  }

  private ProducerRecord<String, String> sent() {
    var captor = ArgumentCaptor.<ProducerRecord<String, String>>captor();
    verify(template).send(captor.capture());
    return captor.getValue();
  }

  @Test
  void routesByAggregateTypeKeyedByAggregateId() throws Exception {
    publisher.publish(message("Procurement", Map.of()));

    assertThat(sent().topic()).isEqualTo("radar.procurement.v1");
    assertThat(sent().key()).isEqualTo("PNCP-1");
  }

  @Test
  void alertAggregatesGoToTheAlertTopic() throws Exception {
    publisher.publish(message("Alert", Map.of()));

    assertThat(sent().topic()).isEqualTo("radar.alert.v1");
  }

  @Test
  void valueIsTheFullEnvelopeBuiltFromTheRow() throws Exception {
    publisher.publish(
        message(
            "Procurement",
            Map.of("aggregateVersion", 3, "traceparent", "00-abc-def-01", "eventType", "x")));

    var envelope = JSON.readTree(sent().value());
    assertThat(envelope.propertyNames())
        .containsExactly(
            "eventId",
            "eventType",
            "schemaVersion",
            "aggregateType",
            "aggregateId",
            "aggregateVersion",
            "occurredAt",
            "traceparent",
            "payload");
    assertThat(envelope.get("eventId").asString()).isEqualTo(EVENT_ID.toString());
    assertThat(envelope.get("eventType").asString()).isEqualTo("ProcurementUpdated");
    assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(2);
    assertThat(envelope.get("aggregateType").asString()).isEqualTo("Procurement");
    assertThat(envelope.get("aggregateId").asString()).isEqualTo("PNCP-1");
    assertThat(envelope.get("aggregateVersion").asInt()).isEqualTo(3);
    assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-22T12:00:00Z");
    assertThat(envelope.get("traceparent").asString()).isEqualTo("00-abc-def-01");
    assertThat(envelope.get("payload").get("nested").get("a").get(1).asInt()).isEqualTo(2);
  }

  @Test
  void occurredAtComesFromTheRowHeadersWhenPresent() throws Exception {
    publisher.publish(message("Procurement", Map.of("occurredAt", "2026-10-21T08:00:00Z")));

    assertThat(JSON.readTree(sent().value()).get("occurredAt").asString())
        .isEqualTo("2026-10-21T08:00:00Z");
  }

  @Test
  void aPayloadThatIsNotJsonIsAPublishExceptionAndNothingIsSent() {
    var broken =
        new OutboxMessage(
            8,
            EVENT_ID,
            "Procurement",
            "PNCP-1",
            "ProcurementUpdated",
            1,
            "{not json",
            Map.of(),
            Instant.parse("2026-10-22T12:00:00Z"),
            0);

    assertThatThrownBy(() -> publisher.publish(broken))
        .isInstanceOf(PublishException.class)
        .isNotInstanceOf(BrokerUnavailableException.class)
        .hasMessageContaining("PNCP-1");
    verifyNoInteractions(template);
  }

  @Test
  void absentTraceparentIsJsonNullAndHasNoHeader() throws Exception {
    publisher.publish(message("Procurement", Map.of()));

    assertThat(JSON.readTree(sent().value()).get("traceparent").isNull()).isTrue();
    assertThat(sent().headers().lastHeader("traceparent")).isNull();
  }

  @Test
  void headersCarryEventIdTypeSchemaVersionAndTraceparent() throws Exception {
    publisher.publish(message("Procurement", Map.of("traceparent", "00-abc-def-01")));

    var headers = sent().headers();
    assertThat(new String(headers.lastHeader("eventId").value())).isEqualTo(EVENT_ID.toString());
    assertThat(new String(headers.lastHeader("eventType").value())).isEqualTo("ProcurementUpdated");
    assertThat(new String(headers.lastHeader("schemaVersion").value())).isEqualTo("2");
    assertThat(new String(headers.lastHeader("traceparent").value())).isEqualTo("00-abc-def-01");
  }

  @Test
  void unknownAggregateTypeIsAPublishException() {
    assertThatThrownBy(() -> publisher.publish(message("Nope", Map.of())))
        .isInstanceOf(PublishException.class)
        .hasMessageContaining("Nope");
  }

  @Test
  void brokerFailureIsAPublishExceptionWithTheCause() {
    var boom = new IllegalStateException("not enough replicas");
    when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(boom));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(PublishException.class)
        .hasRootCause(boom);
  }

  @Test
  void synchronousSendFailureIsAPublishException() {
    when(template.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("closed"));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(PublishException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void noAckWithinThePublishTimeoutMeansTheBrokerIsUnavailable() {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(BrokerUnavailableException.class)
        .hasMessageContaining("PNCP-1");
  }

  @Test
  void aKafkaTimeoutOrOtherRetriableErrorMeansTheBrokerIsUnavailable() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(
            CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("metadata not available")));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(BrokerUnavailableException.class);
  }

  @Test
  void aMessageSpecificBrokerErrorIsNotBrokerWide() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too big")));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(PublishException.class)
        .isNotInstanceOf(BrokerUnavailableException.class);
  }

  @Test
  void interruptionIsAPublishExceptionAndKeepsTheInterruptFlag() {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(PublishException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  private static ProducerRecord<String, String> anyRecord() {
    return new ProducerRecord<>("radar.procurement.v1", "PNCP-1", "{}");
  }

  /** What the template completes the future with: the cause wrapped in a KafkaProducerException. */
  private static KafkaProducerException wrapped(Throwable cause) {
    return new KafkaProducerException(anyRecord(), "Failed to send", cause);
  }

  static Stream<Throwable> brokerWideShapes() {
    return Stream.of(
        wrapped(new org.apache.kafka.common.errors.TimeoutException("metadata not available")),
        wrapped(new NetworkException("connection reset")),
        wrapped(new DisconnectException()),
        wrapped(new NotEnoughReplicasException("min isr")),
        wrapped(new BufferExhaustedException("buffer full")),
        new NetworkException("thrown straight from send"),
        new BufferExhaustedException("thrown straight from send"),
        new KafkaException(
            "producer failed", new org.apache.kafka.common.errors.TimeoutException("t")),
        new CompletionException(wrapped(new NetworkException("deep"))));
  }

  @ParameterizedTest
  @MethodSource("brokerWideShapes")
  void failedFuturesOfBrokerWideShapesMeanTheBrokerIsUnavailable(Throwable failure) {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(failure));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(BrokerUnavailableException.class);
  }

  @ParameterizedTest
  @MethodSource("brokerWideShapes")
  void synchronousFailuresOfBrokerWideShapesMeanTheBrokerIsUnavailable(Throwable failure) {
    when(template.send(any(ProducerRecord.class)))
        .thenThrow(new RuntimeException("wrapper", failure));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(BrokerUnavailableException.class);
  }

  static Stream<Throwable> messageSpecificShapes() {
    return Stream.of(
        wrapped(new RecordTooLargeException("too big")),
        wrapped(new SerializationException("bad value")),
        wrapped(new InvalidTopicException("bad topic name")),
        new IllegalStateException("producer closed"),
        new KafkaException("something else"));
  }

  @ParameterizedTest
  @MethodSource("messageSpecificShapes")
  void messageSpecificShapesAreNotBrokerWide(Throwable failure) {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(failure));

    assertThatThrownBy(() -> publisher.publish(message("Procurement", Map.of())))
        .isInstanceOf(PublishException.class)
        .isNotInstanceOf(BrokerUnavailableException.class);
  }
}
