package br.dev.miguelsanzbr.radar.alerts.application;

public class EmailAlreadyRegisteredException extends RuntimeException {

  public EmailAlreadyRegisteredException() {
    this("e-mail already registered");
  }

  private EmailAlreadyRegisteredException(String message) {
    super(message);
  }

  /** The identity provider account is already linked to another subscriber. */
  public static EmailAlreadyRegisteredException accountAlreadySubscribed() {
    return new EmailAlreadyRegisteredException("this account already has a subscriber");
  }
}
