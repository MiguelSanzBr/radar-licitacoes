package br.dev.miguelsanzbr.radar.outbox;

/**
 * The failure is not about this message but about the broker; the rest of the batch would fail too.
 */
public class BrokerUnavailableException extends PublishException {

  public BrokerUnavailableException(String message) {
    super(message);
  }

  public BrokerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
