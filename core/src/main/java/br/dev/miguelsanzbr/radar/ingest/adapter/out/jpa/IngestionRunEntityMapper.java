package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.RunStatus;

final class IngestionRunEntityMapper {

  private IngestionRunEntityMapper() {}

  /** Copies the run state onto the entity; id and version stay the entity's. */
  static void copyState(IngestionRun run, IngestionRunEntity entity) {
    entity.kind = run.kind().name();
    entity.modalityId = run.modalityId();
    entity.windowStart = run.windowStart();
    entity.windowEnd = run.windowEnd();
    entity.cursorPage = run.cursorPage();
    entity.status = run.status().name();
    entity.fetched = run.fetched();
    entity.discovered = run.discovered();
    entity.updated = run.updated();
    entity.unchanged = run.unchanged();
    entity.startedAt = run.startedAt();
    entity.finishedAt = run.finishedAt();
    entity.error = run.error();
  }

  static IngestionRun toDomain(IngestionRunEntity entity) {
    return IngestionRun.restore(
        entity.id,
        entity.version,
        IngestionKind.valueOf(entity.kind),
        entity.modalityId,
        entity.windowStart,
        entity.windowEnd,
        entity.cursorPage,
        RunStatus.valueOf(entity.status),
        entity.fetched,
        entity.discovered,
        entity.updated,
        entity.unchanged,
        entity.startedAt,
        entity.finishedAt,
        entity.error);
  }
}
