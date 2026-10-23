package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

/** The message is not a procurement event the matcher can read; retrying it cannot help. */
class MalformedEventException extends RuntimeException {

  MalformedEventException(String message) {
    super(message);
  }

  MalformedEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
