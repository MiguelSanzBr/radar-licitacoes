package br.dev.miguelsanzbr.radar.probe;

import java.time.Duration;

/** Seam over blocking waits so cadence is testable with a fake clock. */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration);

  static Sleeper system() {
    return duration -> {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the next slot", e);
      }
    };
  }
}
