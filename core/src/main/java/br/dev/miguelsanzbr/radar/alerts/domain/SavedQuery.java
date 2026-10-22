package br.dev.miguelsanzbr.radar.alerts.domain;

/** Text and filters of a saved search; mirrors the search context without depending on it. */
public record SavedQuery(String text, AlertFilters filters) {

  public static final int MAX_TEXT = 500;

  public SavedQuery {
    if (text == null || text.length() > MAX_TEXT || filters == null) {
      throw new InvalidInputException("query text must have at most 500 characters");
    }
  }
}
