package br.dev.miguelsanzbr.radar.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DecisionTest {

  private static final long W = 60_000;

  @Test
  void aFullBucketAfterOneTakeHasTheCapacityMinusOneLeft() {
    // 60 tokens: level 59 tokens, one token refills per second
    var decision = Decision.of(true, 59 * W, 60, W);

    assertThat(decision).isEqualTo(new Decision(true, 60, 59, 1, 0));
  }

  @Test
  void anEmptyBucketIsDeniedAndAsksToRetryWhenOneTokenIsBack() {
    var decision = Decision.of(false, 0, 60, W);

    assertThat(decision).isEqualTo(new Decision(false, 60, 0, 60, 1));
  }

  @Test
  void aSlowBucketRoundsTheWaitUpToWholeSeconds() {
    // 30 per minute: one token every 2 s; half a token in the bucket means 1 s to the next
    var decision = Decision.of(false, W / 2, 30, W);

    assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    assertThat(decision.resetSeconds()).isEqualTo(59);
    // a token short by one millisecond of refill still waits a whole second
    assertThat(Decision.of(false, W - 30, 30, W).retryAfterSeconds()).isEqualTo(1);
    assertThat(Decision.of(false, W - 60, 30, W).retryAfterSeconds()).isEqualTo(1);
    assertThat(Decision.of(false, 0, 30, W).retryAfterSeconds()).isEqualTo(2);
  }
}
