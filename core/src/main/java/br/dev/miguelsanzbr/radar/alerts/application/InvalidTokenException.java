package br.dev.miguelsanzbr.radar.alerts.application;

public class InvalidTokenException extends RuntimeException {

  public InvalidTokenException() {
    super("verification token is invalid, used or expired");
  }
}
