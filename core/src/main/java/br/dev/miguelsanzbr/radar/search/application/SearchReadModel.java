package br.dev.miguelsanzbr.radar.search.application;

import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;

/** Read side port of the search use case. */
public interface SearchReadModel {

  /** A text with no searchable term (only stopwords or punctuation) yields an empty page. */
  SearchPage search(SearchQuery query);
}
