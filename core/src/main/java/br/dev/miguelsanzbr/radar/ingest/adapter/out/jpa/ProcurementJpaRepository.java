package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProcurementJpaRepository extends JpaRepository<ProcurementEntity, ProcurementKey> {

  /** Served by the leading column of the primary key on every partition. */
  List<ProcurementEntity> findByNumeroControlePncpIn(Collection<String> numbers);

  /** Served by the partial index on the closing time (V2): published and not yet closed. */
  @Query(
      """
      select p from ProcurementEntity p
      where p.status = 'PUBLISHED' and p.closedEmittedAt is null and p.proposalClosingAt < ?1
      order by p.proposalClosingAt
      """)
  List<ProcurementEntity> findClosingCandidates(Instant now, Limit limit);
}
