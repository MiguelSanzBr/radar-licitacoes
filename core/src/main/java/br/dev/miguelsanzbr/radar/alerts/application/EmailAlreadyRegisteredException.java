package br.dev.miguelsanzbr.radar.alerts.application;

public class EmailAlreadyRegisteredException extends RuntimeException {

  public EmailAlreadyRegisteredException() {
    super("e-mail already registered");
  }
}
