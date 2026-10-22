package br.dev.miguelsanzbr.radar.alerts.application;

public class IdempotencyMismatchException extends RuntimeException {

  public IdempotencyMismatchException() {
    super("Idempotency-Key was already used with a different body");
  }
}
