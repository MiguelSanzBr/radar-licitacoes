package br.dev.miguelsanzbr.radar.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimitsTest {

  private final RateLimits limits = new RateLimits(60, 600, 30);

  @Test
  void eachTierHasItsOwnLimitPerMinute() {
    assertThat(limits.of(Tier.FREE)).isEqualTo(60);
    assertThat(limits.of(Tier.PRO)).isEqualTo(600);
    assertThat(limits.anonymous()).isEqualTo(30);
  }

  @Test
  void theWindowIsOneMinute() {
    assertThat(RateLimits.WINDOW_MS).isEqualTo(60_000);
  }
}
