package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface IngestionRunJpaRepository extends JpaRepository<IngestionRunEntity, Long> {

  /** At most one row: the partial unique index of V8 allows one unfinished run per window. */
  @Query(
      """
      select r from IngestionRunEntity r
      where r.kind = ?1 and r.modalityId = ?2 and r.windowStart = ?3 and r.windowEnd = ?4
        and r.status in ('RUNNING', 'FAILED')
      """)
  Optional<IngestionRunEntity> findOpen(
      String kind, int modalityId, Instant windowStart, Instant windowEnd);

  boolean existsByKindAndModalityIdAndWindowStartAndWindowEndAndStatus(
      String kind, int modalityId, Instant windowStart, Instant windowEnd, String status);
}
