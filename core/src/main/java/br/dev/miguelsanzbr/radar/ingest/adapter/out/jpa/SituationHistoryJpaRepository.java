package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SituationHistoryJpaRepository extends JpaRepository<SituationHistoryEntity, Long> {

  /** One statement for any number of procurements; the mapper groups the rows by number. */
  @Query(
      "select h from SituationHistoryEntity h where h.numeroControlePncp in :numbers"
          + " order by h.recordedAt, h.id")
  List<SituationHistoryEntity> findAllOrdered(Collection<String> numbers);

  long countByNumeroControlePncp(String numeroControlePncp);
}
