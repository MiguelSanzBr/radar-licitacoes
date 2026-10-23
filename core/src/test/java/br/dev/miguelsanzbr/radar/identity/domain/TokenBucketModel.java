package br.dev.miguelsanzbr.radar.identity.domain;

/**
 * Plain Java mirror of {@code identity/token-bucket.lua}, used as the oracle of the bucket tests.
 * The level counts 1/window of a token, so a refill (elapsed ms times capacity) and the cost of a
 * request (the window) are both integers and nothing is ever rounded.
 */
public final class TokenBucketModel {

  private final int capacity;
  private final long windowMs;
  private Long level;
  private long ts;

  public TokenBucketModel(int capacity, long windowMs) {
    this.capacity = capacity;
    this.windowMs = windowMs;
  }

  public long level() {
    return level;
  }

  public Decision take(long now) {
    var full = capacity * windowMs;
    if (level == null) {
      level = full;
      ts = now;
    }
    if (now > ts) {
      level += (now - ts) * capacity;
      ts = now;
    }
    level = Math.min(level, full);
    var allowed = level >= windowMs;
    if (allowed) {
      level -= windowMs;
    }
    return Decision.of(allowed, level, capacity, windowMs);
  }
}
