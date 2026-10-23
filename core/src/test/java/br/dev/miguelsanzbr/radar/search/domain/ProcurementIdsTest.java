package br.dev.miguelsanzbr.radar.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProcurementIdsTest {

  @Test
  void thePathFormSwapsTheSlashForAHyphen() {
    assertThat(ProcurementIds.toPath("12345678000190-1-000123/2026"))
        .isEqualTo("12345678000190-1-000123-2026");
  }

  @Test
  void onlyTheLastHyphenGoesBackToASlash() {
    assertThat(ProcurementIds.fromPath("12345678000190-1-000123-2026"))
        .isEqualTo("12345678000190-1-000123/2026");
    assertThat(ProcurementIds.fromPath("SYN-12345678000190-1-000123-2026"))
        .isEqualTo("SYN-12345678000190-1-000123/2026");
  }

  @Test
  void aPathWithoutHyphenIsLeftAlone() {
    assertThat(ProcurementIds.fromPath("abc")).isEqualTo("abc");
  }
}
