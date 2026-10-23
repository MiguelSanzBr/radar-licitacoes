package br.dev.miguelsanzbr.radar.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class TokenBucketPropertiesTest {

  private static final long WINDOW = RateLimits.WINDOW_MS;

  @Provide
  Arbitrary<Integer> capacities() {
    return Arbitraries.integers().between(1, 600);
  }

  /** Gaps of 0 ms (bursts) up to two windows, so buckets drain, refill partly and refill fully. */
  @Provide
  Arbitrary<List<Long>> gaps() {
    return Arbitraries.longs().between(0, 2 * WINDOW).list().ofMinSize(1).ofMaxSize(80);
  }

  @Property
  void neverAllowsMoreThanTheCapacityPlusWhatRefilledInAnyStretch(
      @ForAll("capacities") int capacity, @ForAll("gaps") List<Long> gaps) {
    var bucket = new TokenBucketModel(capacity, WINDOW);
    var times = new ArrayList<Long>();
    var allowed = new ArrayList<Boolean>();
    var now = 1_000_000L;
    for (var gap : gaps) {
      now += gap;
      times.add(now);
      allowed.add(bucket.take(now).allowed());
    }

    for (var from = 0; from < times.size(); from++) {
      var count = 0;
      for (var to = from; to < times.size(); to++) {
        count += allowed.get(to) ? 1 : 0;
        var refilled = (times.get(to) - times.get(from)) * capacity / WINDOW;
        assertThat((long) count).isLessThanOrEqualTo(capacity + refilled);
      }
    }
  }

  @Property
  void aBurstAtOneInstantAllowsExactlyTheCapacity(@ForAll("capacities") int capacity) {
    var bucket = new TokenBucketModel(capacity, WINDOW);

    var allowed = 0;
    for (var i = 0; i < capacity + 25; i++) {
      allowed += bucket.take(5_000L).allowed() ? 1 : 0;
    }

    assertThat(allowed).isEqualTo(capacity);
  }

  @Property
  void remainingStaysBetweenZeroAndTheCapacity(
      @ForAll("capacities") int capacity, @ForAll("gaps") List<Long> gaps) {
    var bucket = new TokenBucketModel(capacity, WINDOW);
    var now = 0L;
    for (var gap : gaps) {
      now += gap;
      var decision = bucket.take(now);
      assertThat(decision.limit()).isEqualTo(capacity);
      assertThat(decision.remaining()).isBetween(0, capacity);
      assertThat(decision.resetSeconds()).isBetween(0, 60);
    }
  }

  @Property
  void waitingRetryAfterSecondsAlwaysLetsTheNextRequestIn(
      @ForAll("capacities") int capacity, @ForAll("gaps") List<Long> gaps) {
    var bucket = new TokenBucketModel(capacity, WINDOW);
    var now = 0L;
    for (var gap : gaps) {
      now += gap;
      var decision = bucket.take(now);
      if (!decision.allowed()) {
        assertThat(decision.retryAfterSeconds()).isBetween(1, 60);
        assertThat(bucket.take(now + decision.retryAfterSeconds() * 1000L).allowed()).isTrue();
        return;
      }
    }
  }

  @Property
  void aBucketIdleForAFullWindowIsFullAgain(
      @ForAll("capacities") int capacity, @ForAll("gaps") List<Long> gaps) {
    var bucket = new TokenBucketModel(capacity, WINDOW);
    var now = 0L;
    for (var gap : gaps) {
      now += gap;
      bucket.take(now);
    }

    var decision = bucket.take(now + WINDOW);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(capacity - 1);
  }
}
