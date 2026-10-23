package br.dev.miguelsanzbr.radar.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchQueryTest {

  private static final Cursor BY_RANK = new Cursor.ByRank(0.5, "id");
  private static final Cursor BY_PUBLISHED = new Cursor.ByPublished(Instant.EPOCH, "id");

  @Test
  void theTextIsTrimmed() {
    var query = new SearchQuery("  limpeza predial \n", Filters.NONE, null, 20);

    assertThat(query.text()).isEqualTo("limpeza predial");
    assertThat(query.hasText()).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void noTextMeansEmptyText(String text) {
    var query = new SearchQuery(text, Filters.NONE, null, 20);

    assertThat(query.text()).isEmpty();
    assertThat(query.hasText()).isFalse();
  }

  @Test
  void theLimitDefaultsToTwenty() {
    assertThat(new SearchQuery("x", Filters.NONE, null).limit()).isEqualTo(20);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 20, 50})
  void limitsFromOneToFiftyAreAccepted(int limit) {
    assertThat(new SearchQuery("x", Filters.NONE, null, limit).limit()).isEqualTo(limit);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 51, Integer.MAX_VALUE})
  void limitsOutsideTheRangeAreRejected(int limit) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SearchQuery("x", Filters.NONE, null, limit))
        .withMessageContaining("limit");
  }

  @Test
  void filtersAreRequired() {
    assertThatNullPointerException().isThrownBy(() -> new SearchQuery("x", null, null, 20));
  }

  @Test
  void aRankCursorFitsATextQueryAndAPublishedCursorFitsOneWithoutText() {
    assertThat(new SearchQuery("x", Filters.NONE, BY_RANK, 20).cursor()).isEqualTo(BY_RANK);
    assertThat(new SearchQuery(" ", Filters.NONE, BY_PUBLISHED, 20).cursor())
        .isEqualTo(BY_PUBLISHED);
  }

  @Test
  void aCursorOfTheOtherKindIsRejected() {
    assertThatThrownBy(() -> new SearchQuery("x", Filters.NONE, BY_PUBLISHED, 20))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new SearchQuery("", Filters.NONE, BY_RANK, 20))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void theTextIsCappedAtTwoHundredCharactersAfterTrimming() {
    var max = "a".repeat(200);

    assertThat(new SearchQuery(" " + max + " ", Filters.NONE, null, 20).text()).isEqualTo(max);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SearchQuery(max + "a", Filters.NONE, null, 20))
        .withMessageContaining("200");
  }
}
