package br.dev.miguelsanzbr.radar.alerts.application;

/** The mail server did not accept a message: refused, unreachable or too slow. */
public class MailDeliveryException extends RuntimeException {

  public MailDeliveryException(Throwable cause) {
    super("mail was not accepted by the server", cause);
  }
}
