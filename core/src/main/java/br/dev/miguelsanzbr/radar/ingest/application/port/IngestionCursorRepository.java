package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.ingest.domain.IngestionCursor;
import java.util.Optional;

/** One cursor per modality. */
public interface IngestionCursorRepository {

  Optional<IngestionCursor> find(int modalityId);

  /** Inserts the modality's cursor or replaces the stored one. */
  void save(IngestionCursor cursor);
}
