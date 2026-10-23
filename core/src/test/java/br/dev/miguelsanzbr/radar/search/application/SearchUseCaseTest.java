package br.dev.miguelsanzbr.radar.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchUseCaseTest {

  private static final RankedResult RESULT =
      new RankedResult(
          "id",
          "object",
          "snippet",
          "organ",
          null,
          "SP",
          null,
          null,
          6,
          "PUBLISHED",
          null,
          null,
          null,
          Instant.EPOCH,
          0.5);

  @Test
  void theQueryGoesToTheReadModelAndThePageComesBack() {
    var received = new ArrayList<SearchQuery>();
    SearchReadModel readModel =
        query -> {
          received.add(query);
          return new SearchPage(List.of(RESULT), null);
        };
    var query = new SearchQuery("limpeza", Filters.NONE, null);

    var page = new SearchUseCase(readModel).search(query);

    assertThat(received).containsExactly(query);
    assertThat(page.items()).containsExactly(RESULT);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void thePageKeepsItsItemsImmutable() {
    var items = new ArrayList<>(List.of(RESULT));
    var page = new SearchPage(items, null);
    items.clear();

    assertThat(page.items()).containsExactly(RESULT);
  }
}
