package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.dev.miguelsanzbr.radar.alerts.application.MailDeliveryException;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.exc.StreamReadException;

class NotifierKafkaConfigurationTest {

  private static final ConsumerRecord<String, String> RECORD =
      new ConsumerRecord<>("radar.alert.v1", 3, 7L, "subscriber", "value");

  private static ListenerExecutionFailedException wrapped(Exception cause) {
    return new ListenerExecutionFailedException("listener failed", cause);
  }

  private static Exception smtpDown() {
    return wrapped(new MailDeliveryException(new IllegalStateException("refused")));
  }

  private static boolean recover(DefaultErrorHandler handler, Exception failure) {
    return handler.handleOne(
        failure, RECORD, mock(Consumer.class), mock(MessageListenerContainer.class));
  }

  @Test
  void aMailFailureIsRetriedThreeTimesAndRecoveredOnTheFourthFailure() {
    var recovered = new ArrayList<Exception>();
    var handler =
        NotifierKafkaConfiguration.errorHandler((record, e) -> recovered.add(e), 1L, 3, 5_000L);

    var results = new ArrayList<Boolean>();
    for (var attempt = 0; attempt < 4; attempt++) {
      results.add(recover(handler, smtpDown()));
    }

    // handleOne returns true once the record was recovered (skipped past the retries)
    assertThat(results).containsExactly(false, false, false, true);
    assertThat(recovered).hasSize(1);
  }

  @Test
  void theBackoffGrowsByDoubling() {
    var backOff = NotifierKafkaConfiguration.backOff(1_000L, 3);

    BackOffExecution execution = backOff.start();
    assertThat(List.of(execution.nextBackOff(), execution.nextBackOff(), execution.nextBackOff()))
        .containsExactly(1_000L, 2_000L, 4_000L);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }

  @Test
  void poisonGoesToTheDeadLetterTopicAtOnceWithoutRetries() {
    var poison =
        List.<Exception>of(
            new MalformedEventException("not json"),
            new StreamReadException(null, "not json"),
            new NullPointerException("eventId"),
            new IllegalArgumentException("bad money"));
    var recovered = new ArrayList<Exception>();
    var handler =
        NotifierKafkaConfiguration.errorHandler((record, e) -> recovered.add(e), 1_000L, 3, 5_000L);

    for (var failure : poison) {
      assertThat(recover(handler, wrapped(failure))).as(failure.toString()).isTrue();
    }

    assertThat(recovered).hasSize(4);
  }

  @Test
  void aDatabaseOutageIsRetriedForeverAtTheOutageInterval() {
    var failure = wrapped(new DataAccessResourceFailureException("connection refused"));

    var backOff = (FixedBackOff) NotifierKafkaConfiguration.backOffFor(failure, 1_000L, 3, 5_000L);

    assertThat(backOff.getInterval()).isEqualTo(5_000L);
    assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);
  }

  @Test
  void aMailFailureUsesTheDoublingBackoff() {
    var backOff = NotifierKafkaConfiguration.backOffFor(smtpDown(), 1_000L, 3, 5_000L);

    var execution = backOff.start();
    assertThat(execution.nextBackOff()).isEqualTo(1_000L);
    assertThat(execution.nextBackOff()).isEqualTo(2_000L);
  }

  @Test
  void badDataTheDatabaseRejectsIsRetriedThenDeadLettered() {
    var recovered = new ArrayList<Exception>();
    var handler =
        NotifierKafkaConfiguration.errorHandler((record, e) -> recovered.add(e), 1L, 3, 5_000L);
    var results = new ArrayList<Boolean>();

    for (var attempt = 0; attempt < 4; attempt++) {
      results.add(recover(handler, wrapped(new DataIntegrityViolationException("too long"))));
    }

    assertThat(results).containsExactly(false, false, false, true);
    assertThat(recovered).hasSize(1);
  }
}
