package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PublishExceptionTest {

  @Test
  void keepsTheBrokerFailureAsCause() {
    var cause = new IOException("connection reset");

    var e = new PublishException("not acknowledged", cause);

    assertThat(e).hasMessage("not acknowledged").hasCause(cause);
  }
}
