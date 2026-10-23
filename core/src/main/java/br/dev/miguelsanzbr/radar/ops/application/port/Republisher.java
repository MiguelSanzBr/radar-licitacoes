package br.dev.miguelsanzbr.radar.ops.application.port;

/** Puts a message back on a topic. */
public interface Republisher {

  /** Returns when the broker has the message; any failure is an unchecked exception. */
  void republish(String topic, String key, String value);
}
