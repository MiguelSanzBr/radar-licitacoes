package br.dev.miguelsanzbr.radar.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

class DeadLettersTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

  private final KafkaOperations<String, String> template = mock(KafkaOperations.class);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DeadLetters deadLetters =
      new DeadLetters(template, registry, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void brokerAcknowledges() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
  }

  private ProducerRecord<String, String> dead(String topic, Exception failure) {
    return dead(topic, failure, null);
  }

  private ProducerRecord<String, String> dead(
      String topic, Exception failure, org.apache.kafka.clients.consumer.Consumer<?, ?> consumer) {
    var record = new ConsumerRecord<>(topic, 3, 42L, "key-1", "value-1");
    record
        .headers()
        .add(new RecordHeader("traceparent", "00-abc".getBytes(StandardCharsets.UTF_8)));
    deadLetters.accept(record, consumer, failure);
    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    org.mockito.Mockito.verify(template).send(sent.capture());
    return sent.getValue();
  }

  private static String header(ProducerRecord<?, ?> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  @Test
  void theRecordGoesToTheDeadLetterTopicWithItsKeyAndValueOnAnyPartition() {
    var sent =
        dead(
            "radar.procurement.v1",
            new ListenerExecutionFailedException("x", new IllegalStateException("odd")));

    assertThat(sent.topic()).isEqualTo("radar.procurement.v1.dlq");
    assertThat(sent.partition()).isNull();
    assertThat(sent.key()).isEqualTo("key-1");
    assertThat(sent.value()).isEqualTo("value-1");
  }

  @Test
  void theHeadersTellWhatFailedWhereAndWhen() {
    var sent =
        dead(
            "radar.alert.v1",
            new ListenerExecutionFailedException("wrapper", new IllegalStateException("odd")));

    assertThat(header(sent, DeadLetters.ERROR_CLASS)).isEqualTo("java.lang.IllegalStateException");
    assertThat(header(sent, DeadLetters.ERROR_MESSAGE)).isEqualTo("odd");
    assertThat(header(sent, DeadLetters.ORIGINAL_TOPIC)).isEqualTo("radar.alert.v1");
    assertThat(header(sent, DeadLetters.ORIGINAL_PARTITION)).isEqualTo("3");
    assertThat(header(sent, DeadLetters.ORIGINAL_OFFSET)).isEqualTo("42");
    assertThat(header(sent, DeadLetters.FAILED_AT)).isEqualTo("2026-10-22T12:00:00Z");
    assertThat(header(sent, "traceparent")).isEqualTo("00-abc");
  }

  @Test
  void aFailureWithoutWrapperOrMessageIsDescribedByItsClass() {
    var sent = dead("radar.alert.v1", new NullPointerException());

    assertThat(header(sent, DeadLetters.ERROR_CLASS)).isEqualTo("java.lang.NullPointerException");
    assertThat(header(sent, DeadLetters.ERROR_MESSAGE)).isEmpty();
  }

  @Test
  void aWrapperWithoutACauseIsDescribedByItself() {
    var sent = dead("radar.alert.v1", new ListenerExecutionFailedException("wrapper", null));

    assertThat(header(sent, DeadLetters.ERROR_CLASS))
        .isEqualTo(ListenerExecutionFailedException.class.getName());
  }

  @Test
  void theGroupOfTheFailingConsumerTravelsInAHeader() {
    var consumer = mock(org.apache.kafka.clients.consumer.Consumer.class);
    when(consumer.groupMetadata())
        .thenReturn(
            new org.apache.kafka.clients.consumer.ConsumerGroupMetadata("search-projector"));

    var sent = dead("radar.procurement.v1", new IllegalStateException("odd"), consumer);

    assertThat(header(sent, DeadLetters.CONSUMER_GROUP)).isEqualTo("search-projector");
  }

  @Test
  void withoutAConsumerThereIsNoGroupHeader() {
    var sent = dead("radar.procurement.v1", new IllegalStateException("odd"));

    assertThat(sent.headers().lastHeader(DeadLetters.CONSUMER_GROUP)).isNull();
  }

  @Test
  void aTwoByteMessageIsCutAtOneKilobyteOfUtf8() {
    var sent = dead("radar.alert.v1", new IllegalArgumentException("é".repeat(1_000)));

    assertThat(header(sent, DeadLetters.ERROR_MESSAGE)).isEqualTo("é".repeat(512));
  }

  @Test
  void aThreeByteMessageIsCutWithoutSplittingACharacter() {
    var sent = dead("radar.alert.v1", new IllegalArgumentException("日".repeat(1_000)));

    assertThat(header(sent, DeadLetters.ERROR_MESSAGE)).isEqualTo("日".repeat(341));
  }

  @Test
  void aLongMessageIsCutAtOneKilobyte() {
    var sent = dead("radar.alert.v1", new IllegalArgumentException("x".repeat(5_000)));

    assertThat(header(sent, DeadLetters.ERROR_MESSAGE)).hasSize(1_024).matches("x+");
  }

  @Test
  void noStandardExceptionHeaderRidesAlong() {
    var sent = dead("radar.alert.v1", new IllegalStateException("odd"));

    assertThat(sent.headers().headers("kafka_dlt-exception-stacktrace")).isEmpty();
    assertThat(sent.headers().headers("kafka_dlt-original-topic")).isEmpty();
  }

  @Test
  void eachRecordCountsOnTheCounterOfItsDeadLetterTopic() {
    dead("radar.procurement.v1", new IllegalStateException("odd"));

    assertThat(registry.counter("dlq_messages_total", "topic", "radar.procurement.v1.dlq").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("dlq_messages_total", "topic", "radar.alert.v1.dlq").count())
        .isZero();
  }

  @Test
  void aRecordThatCouldNotBePublishedIsNotCounted() {
    var failed = new CompletableFuture<SendResult<String, String>>();
    failed.completeExceptionally(new IllegalStateException("broker down"));
    when(template.send(any(ProducerRecord.class))).thenReturn(failed);
    var record = new ConsumerRecord<>("radar.alert.v1", 0, 1L, "k", "v");

    assertThatThrownBy(() -> deadLetters.accept(record, null, new IllegalStateException("odd")))
        .isInstanceOf(RuntimeException.class);

    assertThat(registry.counter("dlq_messages_total", "topic", "radar.alert.v1.dlq").count())
        .isZero();
  }

  static Stream<Arguments> outages() {
    return Stream.of(
        Arguments.of("connection failure", new DataAccessResourceFailureException("refused")),
        Arguments.of("query timeout", new QueryTimeoutException("slow")),
        Arguments.of("lock failure", new CannotAcquireLockException("deadlock")),
        Arguments.of("no transaction", new CannotCreateTransactionException("no connection")),
        Arguments.of("transaction timeout", new TransactionTimedOutException("late")),
        Arguments.of(
            "outage in the cause chain",
            new ListenerExecutionFailedException(
                "x", new IllegalStateException("w", new DataAccessResourceFailureException("r")))));
  }

  static Stream<Arguments> sqlOutages() {
    return Stream.of(
        Arguments.of("deadlock", sql("40P01")),
        Arguments.of("serialization failure", sql("40001")),
        Arguments.of("lock not available", sql("55P03")),
        Arguments.of("query canceled", sql("57014")),
        Arguments.of("connection failure", sql("08006")),
        Arguments.of("too many connections", sql("53300")),
        Arguments.of("transient by type", new java.sql.SQLTransientException("slow")),
        Arguments.of("recoverable by type", new java.sql.SQLRecoverableException("retry")),
        Arguments.of(
            "connection by type", new java.sql.SQLNonTransientConnectionException("closed")));
  }

  static Stream<Arguments> sqlNotOutages() {
    return Stream.of(
        Arguments.of("unique violation", sql("23505")),
        Arguments.of("value too long", sql("22001")),
        Arguments.of("no state", new java.sql.SQLException("odd")));
  }

  private static org.jooq.exception.DataAccessException sql(String state) {
    return new org.jooq.exception.DataAccessException(
        "jooq", new java.sql.SQLException("db", state));
  }

  static Stream<Arguments> notOutages() {
    return Stream.of(
        Arguments.of("integrity violation", new DataIntegrityViolationException("too long")),
        Arguments.of("illegal state", new IllegalStateException("odd")),
        Arguments.of(
            "poison in a wrapper",
            new ListenerExecutionFailedException("x", new NullPointerException("eventId"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outages")
  void anOutageOfTheDatabaseIsRecognised(String label, Exception failure) {
    assertThat(DeadLetters.isOutage(failure)).isTrue();
  }

  @ParameterizedTest(name = "raw jOOQ: {0}")
  @MethodSource("sqlOutages")
  void aRawJooqFailureOfTheDatabaseIsAnOutage(String label, Exception failure) {
    var raw =
        failure instanceof org.jooq.exception.DataAccessException
            ? failure
            : new org.jooq.exception.DataAccessException("jooq", failure);
    assertThat(DeadLetters.isOutage(new ListenerExecutionFailedException("x", raw))).isTrue();
  }

  @ParameterizedTest(name = "raw jOOQ: {0}")
  @MethodSource("sqlNotOutages")
  void aRawJooqRejectionOfTheDataIsNotAnOutage(String label, Exception failure) {
    var raw =
        failure instanceof org.jooq.exception.DataAccessException
            ? failure
            : new org.jooq.exception.DataAccessException("jooq", failure);
    assertThat(DeadLetters.isOutage(new ListenerExecutionFailedException("x", raw))).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("notOutages")
  void badDataIsNotAnOutage(String label, Exception failure) {
    assertThat(DeadLetters.isOutage(failure)).isFalse();
  }
}
