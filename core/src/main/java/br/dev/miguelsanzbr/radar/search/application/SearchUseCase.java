package br.dev.miguelsanzbr.radar.search.application;

import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;

/** Full-text search over the procurement read model. */
public class SearchUseCase {

  private final SearchReadModel readModel;

  public SearchUseCase(SearchReadModel readModel) {
    this.readModel = readModel;
  }

  public SearchPage search(SearchQuery query) {
    return readModel.search(query);
  }
}
