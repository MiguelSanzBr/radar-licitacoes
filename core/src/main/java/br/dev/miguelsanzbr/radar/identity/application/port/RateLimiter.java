package br.dev.miguelsanzbr.radar.identity.application.port;

import br.dev.miguelsanzbr.radar.identity.application.RateLimiterUnavailableException;
import br.dev.miguelsanzbr.radar.identity.domain.Decision;

public interface RateLimiter {

  /** Takes one token from the bucket, which holds {@code capacity} per minute. */
  Decision take(String bucket, int capacity) throws RateLimiterUnavailableException;
}
