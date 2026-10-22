package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import java.time.Instant;
import java.util.Optional;

public interface IngestionRunRepository {

  /**
   * Inserts (id null) or updates the run and returns it with its id and new version: keep working
   * with the returned run.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException when the stored version is
   *     not the one the run was loaded with
   */
  IngestionRun save(IngestionRun run);

  /** The unfinished run (RUNNING or FAILED) of the window, if any. */
  Optional<IngestionRun> findResumable(
      IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd);

  /** Whether the window has a finished run. */
  boolean existsDone(IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd);
}
