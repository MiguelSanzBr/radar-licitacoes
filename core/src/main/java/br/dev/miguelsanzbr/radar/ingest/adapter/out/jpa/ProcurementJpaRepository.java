package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProcurementJpaRepository extends JpaRepository<ProcurementEntity, ProcurementKey> {

  /** Served by the leading column of the primary key on every partition. */
  List<ProcurementEntity> findByNumeroControlePncpIn(Collection<String> numbers);
}
