package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AlertFiltersTest {

  @Test
  void noneHasNoCriteria() {
    assertThat(AlertFilters.NONE).isEqualTo(new AlertFilters(null, null, null, null, null, null));
  }

  @Test
  void valuesAreNormalized() {
    var filters = new AlertFilters(" sp ", "3550308", 6, "10", "1500000.5", true);

    assertThat(filters.uf()).isEqualTo("SP");
    assertThat(filters.ibge()).isEqualTo("3550308");
    assertThat(filters.modality()).isEqualTo(6);
    assertThat(filters.minValue()).isEqualTo("10.00");
    assertThat(filters.maxValue()).isEqualTo("1500000.50");
    assertThat(filters.open()).isTrue();
  }

  @Test
  void invalidUfIbgeOrAmountIsRejected() {
    assertThatThrownBy(() -> new AlertFilters("XX", null, null, null, null, null))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> new AlertFilters(null, "12", null, null, null, null))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> new AlertFilters(null, null, null, "abc", null, null))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> new AlertFilters(null, null, null, null, "-1", null))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void modalityMustBePositive() {
    assertThat(new AlertFilters(null, null, 1, null, null, null).modality()).isEqualTo(1);
    assertThatThrownBy(() -> new AlertFilters(null, null, 0, null, null, null))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void invertedRangeIsRejectedButEqualBoundsAreFine() {
    assertThat(new AlertFilters(null, null, null, "5", "5", null).minValue()).isEqualTo("5.00");
    assertThat(new AlertFilters(null, null, null, "5", null, null).maxValue()).isNull();
    assertThat(new AlertFilters(null, null, null, null, "5", null).minValue()).isNull();
    assertThatThrownBy(() -> new AlertFilters(null, null, null, "6", "5", null))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("minValue");
  }
}
