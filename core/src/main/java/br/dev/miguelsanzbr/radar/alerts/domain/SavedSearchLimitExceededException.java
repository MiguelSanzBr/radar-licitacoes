package br.dev.miguelsanzbr.radar.alerts.domain;

public class SavedSearchLimitExceededException extends RuntimeException {

  public SavedSearchLimitExceededException(int max) {
    super("limit of " + max + " active saved searches reached");
  }
}
