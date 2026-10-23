package br.dev.miguelsanzbr.radar.search.domain;

import java.util.Objects;

/**
 * A page request. Without text the order is by publication date, with text by relevance, and the
 * cursor must be of the matching kind.
 */
public record SearchQuery(String text, Filters filters, Cursor cursor, int limit) {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 50;
  public static final int MAX_TEXT = 200;

  public SearchQuery {
    text = text == null ? "" : text.strip();
    if (text.length() > MAX_TEXT) {
      throw new IllegalArgumentException("text must have at most " + MAX_TEXT + " characters");
    }
    Objects.requireNonNull(filters, "filters");
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    if (cursor instanceof Cursor.ByRank && text.isEmpty()
        || cursor instanceof Cursor.ByPublished && !text.isEmpty()) {
      throw new InvalidCursorException("it belongs to a different kind of query");
    }
  }

  public SearchQuery(String text, Filters filters, Cursor cursor) {
    this(text, filters, cursor, DEFAULT_LIMIT);
  }

  public boolean hasText() {
    return !text.isEmpty();
  }
}
