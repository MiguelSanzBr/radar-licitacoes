package br.dev.miguelsanzbr.radar.ingest.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Read access to the PNCP procurement listings; pages are 1-based. Failures are {@link
 * PncpUnavailableException} (try again later) or {@link PncpProtocolException} (repeating will not
 * help).
 */
public interface PncpGateway {

  /** Procurements published in the window, by modality. */
  PncpPage publications(LocalDate from, LocalDate to, int modalityId, int page);

  /** Procurements updated in the window, by modality. */
  PncpPage updates(LocalDate from, LocalDate to, int modalityId, int page);

  /**
   * When the PNCP's block of this client ends, while one is in force. A caller that sees it can
   * skip its cycle without starting a run that would only fail; the calls themselves fail fast with
   * {@link PncpBlockedException} anyway.
   */
  default Optional<Instant> blockedUntil() {
    return Optional.empty();
  }
}
