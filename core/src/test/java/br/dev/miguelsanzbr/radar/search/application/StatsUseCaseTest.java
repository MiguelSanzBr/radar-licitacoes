package br.dev.miguelsanzbr.radar.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsUseCaseTest {

  @Test
  void theGroupingGoesToTheReadModelAndTheRowsComeBack() {
    var asked = new ArrayList<GroupBy>();
    var rows = List.of(new StatsRow("SP", 3, new BigDecimal("30.00")));
    var useCase =
        new StatsUseCase(
            groupBy -> {
              asked.add(groupBy);
              return rows;
            });

    assertThat(useCase.stats(GroupBy.UF)).isEqualTo(rows);
    assertThat(asked).containsExactly(GroupBy.UF);
  }

  @Test
  void aMissingGroupingIsRejected() {
    var useCase = new StatsUseCase(groupBy -> List.of());

    assertThatNullPointerException().isThrownBy(() -> useCase.stats(null));
  }

  @Test
  void theGroupingsAreTheFourOfTheContract() {
    assertThat(GroupBy.values())
        .containsExactly(GroupBy.UF, GroupBy.MODALITY, GroupBy.MONTH, GroupBy.VALUE_BAND);
  }
}
