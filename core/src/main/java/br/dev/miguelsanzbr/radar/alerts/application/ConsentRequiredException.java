package br.dev.miguelsanzbr.radar.alerts.application;

/** The account is authenticated but never accepted to receive alerts, so it has no subscriber. */
public class ConsentRequiredException extends RuntimeException {

  public ConsentRequiredException() {
    super("consent required before using saved searches");
  }
}
