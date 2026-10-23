package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import java.time.Instant;

/** What the ingest use cases report: notices of a committed page, and PNCP blocks. */
public interface IngestMetrics {

  /** Counts of the notices of one committed page, by what became of them. */
  void itemsProcessed(IngestionKind kind, int discovered, int updated, int unchanged);

  /**
   * A run stopped because the PNCP blocked the client (HTTP {@code status}) until {@code until}.
   */
  void pncpBlocked(int status, Instant until);
}
