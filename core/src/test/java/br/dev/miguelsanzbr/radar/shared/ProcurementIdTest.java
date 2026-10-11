package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcurementIdTest {

  private static final String REAL = "83021808000182-1-000681/2026";

  @Test
  void acceptsARealControlNumberAndPrintsIt() {
    var id = new ProcurementId(REAL);

    assertThat(id.value()).isEqualTo(REAL);
    assertThat(id).hasToString(REAL);
  }

  @Test
  void rejectsNull() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ProcurementId(null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "83021808000182-1-000681-2026",
        "8302180800018-1-000681/2026",
        "83021808000182-1-00068/2026",
        "83021808000182-1-000681/202",
        "83021808000182--000681/2026",
        "83021808000182-1-000681/2026 ",
        " 83021808000182-1-000681/2026",
        "x83021808000182-1-000681/2026",
        "83021808000182-1-000681/2026x",
        "83021808000182-a-000681/2026"
      })
  void rejectsInvalidValuesNamingThem(String bad) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ProcurementId(bad))
        .withMessageContaining("[" + bad + "]");
  }

  @Test
  void acceptsMultiDigitSequence() {
    assertThat(new ProcurementId("83021808000182-12-000681/2026").value())
        .isEqualTo("83021808000182-12-000681/2026");
  }
}
