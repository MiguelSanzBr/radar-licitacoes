package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.bare;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.discovered;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.withPublishedAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@PostgresJpaTest
class JpaProcurementRepositoryIT {

  @Autowired ProcurementRepository repository;
  @Autowired TestEntityManager entityManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

  private Procurement reload(ProcurementId id) {
    entityManager.flush();
    entityManager.clear();
    return repository.findById(id).orElseThrow();
  }

  @Test
  void savedProcurementReloadsWithEveryField() {
    var original = discovered(snapshot(id(1)));

    var saved = repository.save(original);

    assertThat(saved.version()).isZero();
    // raw is compared semantically in its own test: jsonb normalizes key order and spacing
    assertThat(reload(id(1)))
        .usingRecursiveComparison()
        .ignoringFields("current.rawJson")
        .isEqualTo(original);
  }

  @Test
  void absentOptionalFieldsComeBackAbsent() {
    var original = discovered(bare(id(2)));

    repository.save(original);

    var reloaded = reload(id(2));
    assertThat(reloaded).usingRecursiveComparison().isEqualTo(original);
    assertThat(reloaded.unit().uf()).isNull();
    assertThat(reloaded.proposalWindow()).isNull();
    assertThat(reloaded.estimatedValue()).isNull();
  }

  @Test
  void moneyKeepsScaleTwo() {
    repository.save(discovered(snapshot(id(3))));

    assertThat(reload(id(3)).estimatedValue().amount()).hasToString("1500.50");
  }

  @Test
  void rawIsStoredAsAJsonbObjectAndReadsBackSemanticallyEqual() {
    repository.save(discovered(snapshot(id(4))));
    var reloaded = reload(id(4));

    var stored =
        jdbc.queryForMap(
            "select jsonb_typeof(raw) as type, raw = ?::jsonb as same"
                + " from ingest.procurement where numero_controle_pncp = ?",
            ProcurementFixtures.RAW,
            id(4).value());
    assertThat(stored).containsEntry("type", "object").containsEntry("same", true);
    var equal =
        jdbc.queryForObject(
            "select ?::jsonb = ?::jsonb",
            Boolean.class,
            reloaded.rawJson(),
            ProcurementFixtures.RAW);
    assertThat(equal).isTrue();
  }

  @Test
  void findByIdOfUnknownProcurementIsEmpty() {
    assertThat(repository.findById(id(404))).isEmpty();
  }

  @Test
  void appliedSnapshotIsPersistedAndHistoryGrowsByTheNewEntriesOnly() {
    repository.save(discovered(snapshot(id(5))));
    var loaded = reload(id(5));

    loaded.applySnapshot(
        snapshot(id(5), 4, "Objeto revisado"), clockAt(T0.plus(Duration.ofHours(1))));
    var saved = repository.save(loaded);

    assertThat(saved.version()).isEqualTo(1);
    var reloaded = reload(id(5));
    assertThat(reloaded.situation().id()).isEqualTo(4);
    assertThat(reloaded.objectDescription()).isEqualTo("Objeto revisado");
    assertThat(reloaded.history()).hasSize(2);
    assertThat(reloaded)
        .usingRecursiveComparison()
        .ignoringFields("version", "current.rawJson")
        .isEqualTo(loaded);

    repository.save(reloaded);
    assertThat(historyRows(id(5))).isEqualTo(2);
  }

  @Test
  void updateWithoutSituationChangeAddsNoHistory() {
    repository.save(discovered(snapshot(id(6))));
    var loaded = reload(id(6));

    loaded.applySnapshot(snapshot(id(6), 1, "Outro objeto"), clockAt(T0.plus(Duration.ofHours(1))));
    repository.save(loaded);

    assertThat(reload(id(6)).history()).hasSize(1);
    assertThat(historyRows(id(6))).isEqualTo(1);
  }

  @Test
  void findAllByIdReturnsOnlyTheRequestedProcurements() {
    repository.save(discovered(snapshot(id(7))));
    repository.save(discovered(snapshot(id(8))));
    repository.save(discovered(snapshot(id(9))));
    entityManager.flush();
    entityManager.clear();

    var found = repository.findAllById(List.of(id(7), id(9), id(404)));

    assertThat(found).extracting(Procurement::id).containsExactlyInAnyOrder(id(7), id(9));
  }

  @Test
  void secondWriterOfTheSameVersionFailsWithOptimisticLock() {
    repository.save(discovered(snapshot(id(10))));
    var first = reload(id(10));
    entityManager.clear();
    var second = repository.findById(id(10)).orElseThrow();

    first.applySnapshot(snapshot(id(10), 4, "Primeiro"), clockAt(T0.plus(Duration.ofHours(1))));
    second.applySnapshot(snapshot(id(10), 2, "Segundo"), clockAt(T0.plus(Duration.ofHours(2))));
    repository.save(first);

    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void insertInAMonthWithoutPartitionFailsLoudly() {
    var publishedIn2031 = withPublishedAt(snapshot(id(11)), Instant.parse("2031-01-15T00:00:00Z"));

    assertThatThrownBy(() -> repository.save(discovered(publishedIn2031)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void heartbeatKeepsTheLockVersionButPersistsLastSeenAt() {
    var snapshot = snapshot(id(12));
    repository.save(discovered(snapshot));
    var loaded = reload(id(12));
    var later = T0.plus(Duration.ofHours(3));

    loaded.applySnapshot(snapshot, clockAt(later));
    var saved = repository.save(loaded);

    assertThat(saved.version()).isZero();
    assertThat(saved.lastSeenAt()).isEqualTo(later);
    var stored =
        jdbc.queryForObject(
            "select last_seen_at from ingest.procurement where numero_controle_pncp = ?",
            java.sql.Timestamp.class,
            id(12).value());
    assertThat(stored.toInstant()).isEqualTo(later);
  }

  @Test
  void historyComesBackOrderedByRecordedAtEvenWhenInsertedOutOfOrder() {
    repository.save(discovered(snapshot(id(13))));
    var insert =
        "insert into ingest.procurement_situation_history (numero_controle_pncp, from_situation_id,"
            + " to_situation_id, pncp_updated_at, recorded_at) values (?, 1, 4, ?::timestamptz, ?::timestamptz)";
    jdbc.update(insert, id(13).value(), "2026-10-22T10:00:00Z", "2026-10-22T15:00:00Z");
    jdbc.update(insert, id(13).value(), "2026-10-22T10:00:00Z", "2026-10-22T13:00:00Z");

    var history = reload(id(13)).history();

    assertThat(history)
        .extracting(h -> h.recordedAt())
        .containsExactly(
            T0, Instant.parse("2026-10-22T13:00:00Z"), Instant.parse("2026-10-22T15:00:00Z"));
  }

  @Test
  void loadingMoreThanAThousandIdsIsSplitIntoBatchesOfAThousand() {
    repository.save(discovered(snapshot(id(14))));
    entityManager.flush();
    entityManager.clear();
    var ids =
        java.util.stream.IntStream.rangeClosed(1, 1001)
            .mapToObj(i -> id(500_000 + i))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    ids.add(id(14));
    var statistics =
        entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    statistics.clear();

    var found = repository.findAllById(ids);

    assertThat(found).extracting(Procurement::id).containsExactly(id(14));
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
  }

  private int historyRows(ProcurementId id) {
    return jdbc.queryForObject(
        "select count(*) from ingest.procurement_situation_history where numero_controle_pncp = ?",
        Integer.class,
        id.value());
  }

  @Test
  void closingCandidatesAreTheExpiredPublishedNoticesNotYetClosedOldestFirstUpToTheLimit() {
    var afterClosing = T0.plus(Duration.ofDays(6));
    repository.save(discovered(snapshot(id(70))));
    repository.save(discovered(snapshot(id(71))));
    repository.save(discovered(snapshot(id(72))));
    repository.save(discovered(snapshot(id(73), 2, "revogada")));
    var alreadyClosed = discovered(snapshot(id(74)));
    alreadyClosed.closeIfExpired(clockAt(afterClosing));
    repository.save(alreadyClosed);
    repository.save(discovered(bare(id(75))));
    entityManager.flush();
    entityManager.clear();

    var all = repository.findClosingCandidates(afterClosing, 10);
    var limited = repository.findClosingCandidates(afterClosing, 2);

    assertThat(all).extracting(Procurement::id).containsExactlyInAnyOrder(id(70), id(71), id(72));
    assertThat(limited).hasSize(2);
    assertThat(repository.findClosingCandidates(T0.plus(Duration.ofDays(5)), 10)).isEmpty();
  }
}
