package br.dev.miguelsanzbr.radar.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.dev.miguelsanzbr.radar.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FiltersTest {

  @Test
  void noFiltersIsValid() {
    assertThat(Filters.NONE).isEqualTo(new Filters(null, null, null, null, null, null, null));
  }

  @Test
  void aValueRangeAcceptsEqualBoundsAndOpenEnds() {
    new Filters(null, null, null, Money.of("10"), Money.of("10.00"), null, null);
    new Filters(null, null, null, Money.of("10"), null, null, null);
    new Filters(null, null, null, null, Money.of("10"), null, null);
  }

  @Test
  void aMinimumAboveTheMaximumIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new Filters(null, null, null, Money.of("10.01"), Money.of("10"), null, null))
        .withMessageContaining("minValue");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PUBLISHED", "SUSPENDED", "REVOKED", "ANNULLED"})
  void theFourStatusesAreAccepted(String status) {
    assertThat(new Filters(null, null, null, null, null, null, status).status()).isEqualTo(status);
  }

  @ParameterizedTest
  @ValueSource(strings = {"published", "OPEN", "", " "})
  void anyOtherStatusIsRejected(String status) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Filters(null, null, null, null, null, null, status))
        .withMessageContaining("status");
  }

  @Test
  void onlyAnExplicitTrueMeansOpen() {
    assertThat(new Filters(null, null, null, null, null, true, null).openOnly()).isTrue();
    assertThat(new Filters(null, null, null, null, null, false, null).openOnly()).isFalse();
    assertThat(Filters.NONE.openOnly()).isFalse();
  }
}
