package br.dev.miguelsanzbr.radar.alerts.adapter.out.mail;

import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder until the notifier (PR 3.3) sends the link through Mailpit. Never logs the token. */
@Component
class LoggingVerificationMailer implements VerificationMailer {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingVerificationMailer.class);

  @Override
  public void send(String email, String rawToken) {
    LOG.info("verification link issued, delivery pending the e-mail notifier");
  }
}
