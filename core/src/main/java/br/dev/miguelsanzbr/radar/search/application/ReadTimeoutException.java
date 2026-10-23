package br.dev.miguelsanzbr.radar.search.application;

/** A read of the search side ran past the configured time and was cancelled. */
public class ReadTimeoutException extends RuntimeException {

  public ReadTimeoutException() {
    super("read cancelled after the statement timeout");
  }
}
