package br.dev.miguelsanzbr.radar.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrganSearchUseCaseTest {

  private final List<String> askedFor = new ArrayList<>();
  private final List<Integer> limits = new ArrayList<>();
  private final OrganSearchUseCase useCase =
      new OrganSearchUseCase(
          (q, limit) -> {
            askedFor.add(q);
            limits.add(limit);
            return List.of(new OrganMatch("Prefeitura", 0.5f));
          });

  @Test
  void theTrimmedTextAndTheLimitGoToTheReadModel() {
    var matches = useCase.search("  prefeitra ", 7);

    assertThat(matches).containsExactly(new OrganMatch("Prefeitura", 0.5f));
    assertThat(askedFor).containsExactly("prefeitra");
    assertThat(limits).containsExactly(7);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10, 20})
  void limitsInsideTheRangeAreAccepted(int limit) {
    assertThat(useCase.search("prefeitura", limit)).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void aBlankTextIsRejected(String q) {
    assertThatIllegalArgumentException().isThrownBy(() -> useCase.search(q, 10));
    assertThat(askedFor).isEmpty();
  }

  @Test
  void aMissingTextIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> useCase.search(null, 10));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 21, Integer.MAX_VALUE})
  void limitsOutsideTheRangeAreRejected(int limit) {
    assertThatIllegalArgumentException().isThrownBy(() -> useCase.search("prefeitura", limit));
    assertThat(askedFor).isEmpty();
  }

  @Test
  void theDefaultLimitIsTenAndTheMaximumTwenty() {
    assertThat(OrganSearchUseCase.DEFAULT_LIMIT).isEqualTo(10);
    assertThat(OrganSearchUseCase.MAX_LIMIT).isEqualTo(20);
  }
}
