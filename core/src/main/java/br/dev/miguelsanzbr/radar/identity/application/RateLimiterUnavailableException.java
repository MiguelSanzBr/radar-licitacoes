package br.dev.miguelsanzbr.radar.identity.application;

/** The shared counter could not be reached; the caller decides whether to fail open or closed. */
public class RateLimiterUnavailableException extends RuntimeException {

  public RateLimiterUnavailableException(Throwable cause) {
    super("rate limiter unavailable", cause);
  }
}
