package br.dev.miguelsanzbr.radar.synth;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/** An empirical distribution: a value is picked in proportion to how often it was seen. */
final class Weighted<T> {

  /** Cumulative count after each value; the first bound above a draw is the value drawn. */
  private final NavigableMap<Long, T> upperBounds = new TreeMap<>();

  private final long total;

  /**
   * A key may be null: an absent field is a value like any other. Insertion order of {@code counts}
   * fixes the picks for a seed, so pass an ordered map.
   */
  Weighted(Map<T, Long> counts) {
    long running = 0;
    for (var entry : counts.entrySet()) {
      running += entry.getValue();
      upperBounds.put(running, entry.getKey());
    }
    total = running;
  }

  T pick(RandomGenerator rng) {
    return upperBounds.higherEntry(rng.nextLong(total)).getValue();
  }
}
