package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;

/** One failed attempt worth retrying; carries the server's {@code Retry-After}, if any. */
final class PncpRetryableException extends PncpUnavailableException {

  private static final long serialVersionUID = 1L;

  private final long retryAfterMillis;

  PncpRetryableException(int status, String message, long retryAfterMillis, Throwable cause) {
    super(status, message, cause);
    this.retryAfterMillis = retryAfterMillis;
  }

  long retryAfterMillis() {
    return retryAfterMillis;
  }
}
