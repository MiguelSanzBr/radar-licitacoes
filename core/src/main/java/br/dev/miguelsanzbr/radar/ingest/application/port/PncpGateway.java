package br.dev.miguelsanzbr.radar.ingest.application.port;

import java.time.LocalDate;

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
}
