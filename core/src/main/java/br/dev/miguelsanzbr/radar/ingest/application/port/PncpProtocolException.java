package br.dev.miguelsanzbr.radar.ingest.application.port;

/**
 * The PNCP answered, but not with something usable: a 4xx other than 429, or a payload that no
 * longer fits the expected shape (an API change signal). Repeating the call will not help. {@code
 * status} is the HTTP status, or 0 for a payload problem.
 */
public class PncpProtocolException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  public PncpProtocolException(int status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
