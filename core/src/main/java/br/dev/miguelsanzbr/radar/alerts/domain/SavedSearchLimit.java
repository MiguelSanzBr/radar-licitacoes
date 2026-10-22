package br.dev.miguelsanzbr.radar.alerts.domain;

/** Maximum number of active saved searches per subscriber. */
public record SavedSearchLimit(int max) {

  public void check(long activeSearches) {
    if (activeSearches >= max) {
      throw new SavedSearchLimitExceededException(max);
    }
  }
}
