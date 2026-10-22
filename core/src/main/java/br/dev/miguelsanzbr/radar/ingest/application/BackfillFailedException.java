package br.dev.miguelsanzbr.radar.ingest.application;

import java.time.LocalDate;

/** A backfill day stopped at a page; the run is saved as FAILED and a rerun resumes there. */
public class BackfillFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public BackfillFailedException(LocalDate day, int page, Throwable cause) {
    super("backfill of " + day + " failed at page " + page + ": " + cause.getMessage(), cause);
  }
}
