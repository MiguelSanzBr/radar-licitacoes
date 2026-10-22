package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaProcurementRepository implements ProcurementRepository {

  /** Keeps the IN list well under the driver's bind-parameter limit. */
  private static final int BATCH_SIZE = 1000;

  private final ProcurementJpaRepository procurements;
  private final SituationHistoryJpaRepository history;

  JpaProcurementRepository(
      ProcurementJpaRepository procurements, SituationHistoryJpaRepository history) {
    this.procurements = procurements;
    this.history = history;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Procurement> findById(ProcurementId id) {
    return findAllById(List.of(id)).stream().findFirst();
  }

  /** Two statements per batch of 1000 ids: the notices, then all their history rows. */
  @Override
  @Transactional(readOnly = true)
  public List<Procurement> findAllById(Collection<ProcurementId> ids) {
    var numbers = ids.stream().map(ProcurementId::value).distinct().toList();
    var found = new ArrayList<Procurement>();
    for (var from = 0; from < numbers.size(); from += BATCH_SIZE) {
      var batch = numbers.subList(from, Math.min(from + BATCH_SIZE, numbers.size()));
      found.addAll(
          ProcurementEntityMapper.toDomain(
              procurements.findByNumeroControlePncpIn(batch), history.findAllOrdered(batch)));
    }
    return found;
  }

  /** Two statements: the candidates, then all their history rows. */
  @Override
  @Transactional(readOnly = true)
  public List<Procurement> findClosingCandidates(Instant now, int limit) {
    var found = procurements.findClosingCandidates(now, Limit.of(limit));
    if (found.isEmpty()) {
      return List.of();
    }
    var numbers = found.stream().map(e -> e.numeroControlePncp).toList();
    return ProcurementEntityMapper.toDomain(found, history.findAllOrdered(numbers));
  }

  /**
   * Flushes so the returned aggregate carries the version the database now holds. History rows are
   * insert-only: only the entries beyond the persisted count are written.
   */
  @Override
  @Transactional
  public Procurement save(Procurement procurement) {
    var number = procurement.id().value();
    var key = new ProcurementKey(number, procurement.publishedAt());
    var entity =
        procurements
            .findById(key)
            .map(managed -> update(managed, procurement))
            .orElseGet(() -> insert(procurement));
    var persisted = history.countByNumeroControlePncp(number);
    history.saveAll(
        procurement.history().stream()
            .skip(persisted)
            .map(entry -> ProcurementEntityMapper.toEntity(number, entry))
            .toList());
    return ProcurementEntityMapper.toDomain(entity, history.findAllOrdered(List.of(number)));
  }

  private ProcurementEntity update(ProcurementEntity entity, Procurement procurement) {
    if (entity.version != procurement.version()) {
      throw new ObjectOptimisticLockingFailureException(ProcurementEntity.class, entity.key());
    }
    ProcurementEntityMapper.copyState(procurement, entity);
    procurements.flush();
    return entity;
  }

  /** A concurrent first discovery of the same notice loses like a stale update: callers retry. */
  private ProcurementEntity insert(Procurement procurement) {
    try {
      return procurements.saveAndFlush(ProcurementEntityMapper.toEntity(procurement));
    } catch (DataIntegrityViolationException e) {
      if (isUniqueViolation(e)) {
        throw new OptimisticLockingFailureException(
            "procurement %s was discovered concurrently".formatted(procurement.id()), e);
      }
      throw e;
    }
  }

  /** SQLSTATE 23505: the primary key was taken between the lookup and the insert. */
  static boolean isUniqueViolation(DataIntegrityViolationException e) {
    return e.getMostSpecificCause() instanceof SQLException sql
        && "23505".equals(sql.getSQLState());
  }
}
