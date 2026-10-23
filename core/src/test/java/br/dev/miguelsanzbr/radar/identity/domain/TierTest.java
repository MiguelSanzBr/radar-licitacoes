package br.dev.miguelsanzbr.radar.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TierTest {

  @Test
  void theColumnValueIsTheLowercaseName() {
    assertThat(Tier.FREE.value()).isEqualTo("free");
    assertThat(Tier.PRO.value()).isEqualTo("pro");
    assertThat(Tier.of("free")).isEqualTo(Tier.FREE);
    assertThat(Tier.of("pro")).isEqualTo(Tier.PRO);
  }

  @Test
  void anUnknownTierIsRefused() {
    assertThatThrownBy(() -> Tier.of("gold")).isInstanceOf(IllegalArgumentException.class);
  }
}
