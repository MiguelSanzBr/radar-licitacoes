package br.dev.miguelsanzbr.radar.ops.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

class DlqKafkaConfigurationTest {

  private static final ConsumerRecord<String, String> RECORD =
      new ConsumerRecord<>("radar.procurement.v1.dlq", 0, 0L, "key", "value");

  private static ListenerExecutionFailedException wrapped(Exception cause) {
    return new ListenerExecutionFailedException("listener failed", cause);
  }

  @Test
  void anOutageOfTheDatabaseIsRetriedForeverEveryFiveSeconds() {
    var backOff =
        (FixedBackOff)
            DlqKafkaConfiguration.backOffFor(
                RECORD, wrapped(new DataAccessResourceFailureException("refused")));

    assertThat(backOff.getInterval()).isEqualTo(5_000L);
    assertThat(backOff.getMaxAttempts()).isEqualTo(FixedBackOff.UNLIMITED_ATTEMPTS);
  }

  @Test
  void aMessageTheDatabaseRejectsIsRetriedThenSkippedBecauseTheTopicStillHoldsIt() {
    var skipped = new ArrayList<Exception>();
    var handler = DlqKafkaConfiguration.errorHandler((record, e) -> skipped.add(e));
    var results = new ArrayList<Boolean>();

    for (var attempt = 0; attempt < 4; attempt++) {
      results.add(
          handler.handleOne(
              wrapped(new DataIntegrityViolationException("NUL in text")),
              RECORD,
              mock(Consumer.class),
              mock(MessageListenerContainer.class)));
    }

    assertThat(results).containsExactly(false, false, false, true);
    assertThat(skipped).hasSize(1);
  }

  @Test
  void aSkippedRecordIsLoggedAndCounted() {
    var registry = new SimpleMeterRegistry();
    var skip = DlqKafkaConfiguration.skipper(registry);
    assertThat(registry.get("dlq_recorder_skipped_total").counter().count()).isZero();

    assertThatCode(() -> skip.accept(RECORD, new IllegalStateException("odd")))
        .doesNotThrowAnyException();

    assertThat(registry.get("dlq_recorder_skipped_total").counter().count()).isEqualTo(1.0);
  }
}
