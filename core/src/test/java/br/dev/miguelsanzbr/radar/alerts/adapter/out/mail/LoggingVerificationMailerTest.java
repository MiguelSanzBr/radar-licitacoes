package br.dev.miguelsanzbr.radar.alerts.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class LoggingVerificationMailerTest {

  @Test
  void acceptsTheMessageWithoutDelivering() {
    assertThatCode(() -> new LoggingVerificationMailer().send("fulano@example.com", "token"))
        .doesNotThrowAnyException();
  }
}
