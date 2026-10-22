package br.dev.miguelsanzbr.radar.probe;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** The single global gate: spaces request starts by an interval that is never below 250 ms. */
final class RateGate {

  static final Duration FLOOR = Duration.ofMillis(250);

  private final InstantSource clock;
  private final Sleeper sleeper;
  private Duration interval;
  private Instant last = Instant.MIN;

  RateGate(InstantSource clock, Sleeper sleeper, Duration interval) {
    this.clock = clock;
    this.sleeper = sleeper;
    interval(interval);
  }

  synchronized void interval(Duration wanted) {
    interval = wanted.compareTo(FLOOR) < 0 ? FLOOR : wanted;
  }

  synchronized void acquire() {
    Instant now = clock.instant();
    Instant allowed = last.plus(interval);
    if (now.isBefore(allowed)) {
      sleeper.sleep(Duration.between(now, allowed));
    }
    last = clock.instant();
  }
}
