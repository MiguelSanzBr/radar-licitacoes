package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbgeCodeTest {

  @ParameterizedTest
  @ValueSource(strings = {"1100015", "2927408", "3550308", "4106902", "5300108"})
  void acceptsSevenDigitsStartingWithRegionOneToFive(String ok) {
    assertThat(new IbgeCode(ok).value()).isEqualTo(ok);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "0100015", "6100015", "9100015", "350030", "35503088", "355030a", " 3550308"})
  void rejectsInvalidValuesNamingThem(String bad) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new IbgeCode(bad))
        .withMessageContaining("[" + bad + "]");
  }

  @Test
  void rejectsNull() {
    assertThatIllegalArgumentException().isThrownBy(() -> new IbgeCode(null));
  }
}
