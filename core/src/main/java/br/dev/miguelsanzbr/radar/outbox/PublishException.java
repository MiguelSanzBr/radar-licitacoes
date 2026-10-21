package br.dev.miguelsanzbr.radar.outbox;

/** The broker did not acknowledge the message; the relay will retry it. */
public class PublishException extends Exception {

  public PublishException(String message) {
    super(message);
  }

  public PublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
