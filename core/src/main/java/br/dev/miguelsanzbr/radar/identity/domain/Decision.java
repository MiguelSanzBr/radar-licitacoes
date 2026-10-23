package br.dev.miguelsanzbr.radar.identity.domain;

/**
 * What the token bucket answered, in the units of the {@code RateLimit-*} headers. {@code
 * resetSeconds} is the time until the bucket is full again; {@code retryAfterSeconds} is the time
 * until one token is back (0 when the request was allowed).
 */
public record Decision(
    boolean allowed, int limit, int remaining, int resetSeconds, int retryAfterSeconds) {

  /**
   * The bucket level counts 1/{@code windowMs} of a token: a request costs {@code windowMs} and
   * each elapsed millisecond refills {@code capacity}, so every figure here is an exact integer.
   */
  public static Decision of(boolean allowed, long level, int capacity, long windowMs) {
    var untilFullMs = Math.ceilDiv(capacity * windowMs - level, capacity);
    var untilTokenMs = Math.ceilDiv(Math.max(0, windowMs - level), capacity);
    return new Decision(
        allowed,
        capacity,
        (int) (level / windowMs),
        (int) Math.ceilDiv(untilFullMs, 1000),
        allowed ? 0 : (int) Math.max(1, Math.ceilDiv(untilTokenMs, 1000)));
  }
}
