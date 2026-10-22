package br.dev.miguelsanzbr.radar.alerts.application;

/** Also raised for another subscriber's id, so ids cannot be probed. */
public class SavedSearchNotFoundException extends RuntimeException {

  public SavedSearchNotFoundException() {
    super("saved search not found");
  }
}
