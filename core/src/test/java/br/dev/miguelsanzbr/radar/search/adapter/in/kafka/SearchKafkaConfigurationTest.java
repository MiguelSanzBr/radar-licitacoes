package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.exc.StreamReadException;

class SearchKafkaConfigurationTest {

  private static final ConsumerRecord<String, String> RECORD =
      new ConsumerRecord<>("radar.procurement.v1", 0, 0L, "key", "value");

  private static ListenerExecutionFailedException wrapped(Exception cause) {
    return new ListenerExecutionFailedException("listener failed", cause);
  }

  static Stream<Arguments> databaseFailures() {
    var outage = new DataAccessResourceFailureException("connection refused");
    return Stream.of(
        Arguments.of("data access", outage),
        Arguments.of("data access in the cause chain", wrapped(outage)),
        Arguments.of(
            "transaction", wrapped(new CannotCreateTransactionException("no connection"))));
  }

  static Stream<Arguments> otherFailures() {
    return Stream.of(
        Arguments.of("illegal state", new IllegalStateException("odd")),
        Arguments.of("poison in a wrapper", wrapped(new NullPointerException("eventId"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("databaseFailures")
  void aDatabaseFailureIsRetriedForeverEveryFiveSeconds(String label, Exception failure) {
    var backOff = (FixedBackOff) SearchKafkaConfiguration.backOffFor(RECORD, failure);

    assertThat(backOff.getInterval()).isEqualTo(5_000L);
    assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("otherFailures")
  void anyOtherFailureGetsAFewQuickRetries(String label, Exception failure) {
    var backOff = (FixedBackOff) SearchKafkaConfiguration.backOffFor(RECORD, failure);

    assertThat(backOff.getInterval()).isEqualTo(1_000L);
    assertThat(backOff.getMaxAttempts()).isEqualTo(3L);
  }

  @Test
  void poisonIsSkippedAtTheFirstFailureWithoutRetries() {
    var poison =
        List.<Exception>of(
            new StreamReadException(null, "not json"),
            new NullPointerException("eventId"),
            new IllegalArgumentException("bad envelope"));
    var skipped = new ArrayList<Exception>();
    var handler = SearchKafkaConfiguration.errorHandler((record, e) -> skipped.add(e));

    for (var failure : poison) {
      var recovered =
          handler.handleOne(
              wrapped(failure), RECORD, mock(Consumer.class), mock(MessageListenerContainer.class));

      assertThat(recovered).as(failure.getClass().getSimpleName()).isTrue();
    }

    assertThat(skipped).hasSize(3);
  }

  @Test
  void aSkippedRecordIsLoggedWithoutThrowing() {
    assertThatCode(
            () ->
                SearchKafkaConfiguration.logAndSkip(RECORD, new IllegalArgumentException("poison")))
        .doesNotThrowAnyException();
  }
}
