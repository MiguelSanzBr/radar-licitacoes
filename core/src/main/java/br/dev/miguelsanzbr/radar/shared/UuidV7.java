package br.dev.miguelsanzbr.radar.shared;

import java.time.InstantSource;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** RFC 9562 UUID version 7: 48-bit epoch millis, then random bits. Not monotonic within a ms. */
public final class UuidV7 {

  private final InstantSource clock;
  private final RandomGenerator random;

  public UuidV7(InstantSource clock, RandomGenerator random) {
    this.clock = clock;
    this.random = random;
  }

  public UUID next() {
    var msb = (clock.millis() << 16) | 0x7000L | (random.nextInt() & 0x0FFFL);
    var lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
