package br.dev.miguelsanzbr.radar.alerts.application;

public class UnauthenticatedException extends RuntimeException {

  public UnauthenticatedException() {
    super("missing or unknown subscriber");
  }
}
