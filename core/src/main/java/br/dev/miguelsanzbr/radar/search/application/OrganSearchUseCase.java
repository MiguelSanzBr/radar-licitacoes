package br.dev.miguelsanzbr.radar.search.application;

import java.util.List;

/** Finds organ names from a text that may have typos. */
public class OrganSearchUseCase {

  public static final int DEFAULT_LIMIT = 10;
  public static final int MAX_LIMIT = 20;

  private final OrganReadModel readModel;

  public OrganSearchUseCase(OrganReadModel readModel) {
    this.readModel = readModel;
  }

  /** {@code q} must not be blank and {@code limit} must be from 1 to {@value #MAX_LIMIT}. */
  public List<OrganMatch> search(String q, int limit) {
    if (q == null || q.isBlank()) {
      throw new IllegalArgumentException("q must not be blank");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
    }
    return readModel.similarTo(q.strip(), limit);
  }
}
