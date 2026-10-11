package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UfTest {

  @Test
  void hasTwentySevenUnits() {
    assertThat(Uf.values()).hasSize(27);
  }

  @Test
  void ofIsCaseInsensitiveAndTrims() {
    assertThat(Uf.of("sp")).isEqualTo(Uf.SP);
    assertThat(Uf.of(" Rj ")).isEqualTo(Uf.RJ);
    assertThat(Uf.of("DF")).isEqualTo(Uf.DF);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "S", "SPP", "XX", "S1", "BR"})
  void ofRejectsAnythingElse(String bad) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Uf.of(bad))
        .withMessageContaining("[" + bad + "]");
  }

  @Test
  void ofRejectsNull() {
    assertThatIllegalArgumentException().isThrownBy(() -> Uf.of(null));
  }
}
