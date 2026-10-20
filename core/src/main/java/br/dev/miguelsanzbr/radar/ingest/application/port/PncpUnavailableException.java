package br.dev.miguelsanzbr.radar.ingest.application.port;

/**
 * The PNCP could not be reached or is not accepting calls: retries exhausted, open circuit, rate or
 * concurrency rejection, transport failure, timeout, 5xx or 429. Trying again later may work.
 * {@code status} is the last HTTP status, or 0 when there was none.
 */
public class PncpUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  public PncpUnavailableException(int status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
