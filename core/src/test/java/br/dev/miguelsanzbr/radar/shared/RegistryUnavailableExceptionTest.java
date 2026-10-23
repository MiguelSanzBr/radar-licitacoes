package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import org.junit.jupiter.api.Test;

class RegistryUnavailableExceptionTest {

  @Test
  void aValueMarkedAsRegistryUnavailableIsRejected() {
    assertThatThrownBy(
            () ->
                RegistryUnavailableException.rejectIfMarked(
                    RegistryUnavailableException.MARKER + "AAAA"))
        .isInstanceOf(RegistryUnavailableException.class)
        .hasMessageContaining("schema registry");
  }

  @Test
  void anyOtherValueIsLeftAlone() {
    assertThatCode(
            () -> {
              RegistryUnavailableException.rejectIfMarked(null);
              RegistryUnavailableException.rejectIfMarked("{\"eventId\":1}");
              RegistryUnavailableException.rejectIfMarked("avro-undecodable:AAAA");
            })
        .doesNotThrowAnyException();
  }

  @Test
  void theErrorHandlersTreatItAsAnOutageSoTheRecordIsRetriedNotDeadLettered() {
    assertThat(DeadLetters.isOutage(new RegistryUnavailableException())).isTrue();
    assertThat(
            DeadLetters.isOutage(
                new org.springframework.kafka.listener.ListenerExecutionFailedException(
                    "wrapped", new RegistryUnavailableException())))
        .isTrue();
  }
}
