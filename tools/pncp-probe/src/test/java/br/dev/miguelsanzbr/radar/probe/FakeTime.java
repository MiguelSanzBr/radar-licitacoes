package br.dev.miguelsanzbr.radar.probe;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** Deterministic clock whose sleeper advances time instead of blocking. */
final class FakeTime implements InstantSource, Sleeper {

  private Instant now;

  FakeTime(Instant start) {
    this.now = start;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public void sleep(Duration duration) {
    now = now.plus(duration);
  }
}
