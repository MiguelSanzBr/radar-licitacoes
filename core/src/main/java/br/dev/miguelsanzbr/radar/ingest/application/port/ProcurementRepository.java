package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Write-side store of {@link Procurement}; ids not found are simply absent from the results. */
public interface ProcurementRepository {

  Optional<Procurement> findById(ProcurementId id);

  List<Procurement> findAllById(Collection<ProcurementId> ids);

  /**
   * Inserts or updates the aggregate and returns it with the new lock version.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException when the stored version is
   *     not the one the aggregate was loaded with
   */
  Procurement save(Procurement procurement);
}
