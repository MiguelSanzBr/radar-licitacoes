package br.dev.miguelsanzbr.radar.outbox;

/** Sends one message to the broker; returns only after the broker acknowledged it. */
public interface EventPublisher {

  void publish(OutboxMessage message) throws PublishException;
}
