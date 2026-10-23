package br.dev.miguelsanzbr.radar.search.domain;

/** The pagination cursor is not one this service issued, or does not fit the query. */
public class InvalidCursorException extends RuntimeException {

  public InvalidCursorException(String message) {
    super("invalid cursor: " + message);
  }
}
