package br.dev.miguelsanzbr.radar.alerts.domain;

/** The subscriber asked for erasure; nothing new may be attached to it. */
public class SubscriberErasedException extends RuntimeException {

  public SubscriberErasedException() {
    super("subscriber was erased");
  }
}
