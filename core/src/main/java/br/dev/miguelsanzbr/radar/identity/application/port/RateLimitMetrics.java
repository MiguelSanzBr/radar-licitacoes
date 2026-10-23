package br.dev.miguelsanzbr.radar.identity.application.port;

import br.dev.miguelsanzbr.radar.identity.application.RateLimiterUnavailableException;

public interface RateLimitMetrics {

  /** A request went through without being metered because the limiter was down. */
  void failedOpen(RateLimiterUnavailableException cause);
}
