package br.dev.miguelsanzbr.radar.ingest.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The PNCP blocked this client (HTTP 429 or 403) and no call is made until {@code until}: the calls
 * that would have been made fail with this exception without a request. Repeating the call sooner
 * only risks a longer block. {@code retryAfter} is what the PNCP asked for, when it did.
 */
public class PncpBlockedException extends PncpUnavailableException {

  private static final long serialVersionUID = 1L;

  private final Instant until;
  private final Duration retryAfter;

  public PncpBlockedException(int status, Instant until, Duration retryAfter) {
    super(status, "PNCP blocked the client (HTTP " + status + "), no calls until " + until, null);
    this.until = until;
    this.retryAfter = retryAfter;
  }

  public Instant until() {
    return until;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
