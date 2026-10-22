package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.RunStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaIngestionRunRepository implements IngestionRunRepository {

  private final IngestionRunJpaRepository runs;

  JpaIngestionRunRepository(IngestionRunJpaRepository runs) {
    this.runs = runs;
  }

  /**
   * Joins the page transaction when there is one; saved alone it commits on its own. A run saved
   * from a stale copy loses, like a stale procurement.
   */
  @Override
  @Transactional
  public IngestionRun save(IngestionRun run) {
    var entity =
        run.id() == null
            ? new IngestionRunEntity()
            : runs.findById(run.id()).orElseThrow(() -> unknown(run));
    if (run.id() != null && entity.version != run.version()) {
      throw new ObjectOptimisticLockingFailureException(IngestionRunEntity.class, run.id());
    }
    IngestionRunEntityMapper.copyState(run, entity);
    return IngestionRunEntityMapper.toDomain(runs.saveAndFlush(entity));
  }

  private static IllegalStateException unknown(IngestionRun run) {
    return new IllegalStateException("ingestion run " + run.id() + " does not exist");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<IngestionRun> findResumable(
      IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd) {
    return runs.findOpen(kind.name(), modalityId, windowStart, windowEnd)
        .map(IngestionRunEntityMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsDone(
      IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd) {
    return runs.existsByKindAndModalityIdAndWindowStartAndWindowEndAndStatus(
        kind.name(), modalityId, windowStart, windowEnd, RunStatus.DONE.name());
  }
}
