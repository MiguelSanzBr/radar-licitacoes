package br.dev.miguelsanzbr.radar.ingest.application;

/** An incremental sync stopped at a page; the run is saved as FAILED and the cursor kept. */
public class IncrementalSyncFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IncrementalSyncFailedException(int modalityId, int page, Throwable cause) {
    super(
        "incremental sync of modality "
            + modalityId
            + " failed at page "
            + page
            + ": "
            + cause.getMessage(),
        cause);
  }
}
