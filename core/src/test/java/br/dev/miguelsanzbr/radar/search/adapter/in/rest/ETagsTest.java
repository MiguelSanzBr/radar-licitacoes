package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ETagsTest {

  @Test
  void aWeakTagQuotesTheSha256OfTheBody() {
    assertThat(ETags.weak("abc"))
        .isEqualTo("W/\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"");
  }

  @Test
  void onlyAStarMatchesAnyRepresentation() {
    assertThat(ETags.matchesAny("*")).isTrue();
    assertThat(ETags.matchesAny(" * ")).isTrue();
    assertThat(ETags.matchesAny("\"a\", *")).isFalse();
    assertThat(ETags.matchesAny("\"a\"")).isFalse();
    assertThat(ETags.matchesAny(null)).isFalse();
  }

  @Test
  void theHashIsLowercaseHex() {
    assertThat(ETags.sha256("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void anUnknownAlgorithmIsReportedAsAnIllegalState() {
    assertThatThrownBy(() -> ETags.digest("NOPE", "abc"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOPE");
  }
}
