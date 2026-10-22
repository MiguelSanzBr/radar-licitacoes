package br.dev.miguelsanzbr.radar.alerts.domain;

import java.util.function.Supplier;

/** A value broke a domain rule; the only exception the web layer turns into a 422. */
public class InvalidInputException extends IllegalArgumentException {

  public InvalidInputException(String message) {
    super(message);
  }

  public InvalidInputException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Runs a shared-kernel check and re-throws its plain argument error as a domain one. */
  public static <T> T guard(Supplier<T> check) {
    try {
      return check.get();
    } catch (IllegalArgumentException e) {
      throw new InvalidInputException(e.getMessage(), e);
    }
  }
}
