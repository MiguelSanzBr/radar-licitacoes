package br.dev.miguelsanzbr.radar.ingest.application;

import java.time.LocalDate;

/** Another backfill of the same day and modality is running in this process. */
public class BackfillWindowBusyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public BackfillWindowBusyException(LocalDate day, int modalityId) {
    super("a backfill of " + day + " (modality " + modalityId + ") is already running");
  }
}
