package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SavedSearchLimitTest {

  private final SavedSearchLimit limit = new SavedSearchLimit(20);

  @Test
  void nineteenActiveSearchesStillAllowOneMore() {
    assertThatCode(() -> limit.check(19)).doesNotThrowAnyException();
  }

  @Test
  void twentyActiveSearchesBlockTheNext() {
    assertThatThrownBy(() -> limit.check(20))
        .isInstanceOf(SavedSearchLimitExceededException.class)
        .hasMessageContaining("20");
  }
}
