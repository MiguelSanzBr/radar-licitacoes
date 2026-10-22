package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;

/** Counts of the notices of one committed page, by what became of them. */
public interface IngestMetrics {

  void itemsProcessed(IngestionKind kind, int discovered, int updated, int unchanged);
}
