package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.discovered;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

@PostgresJpaTest
class ProcurementNPlusOneIT {

  private static final Logger LOG = LoggerFactory.getLogger(ProcurementNPlusOneIT.class);
  private static final int PROCUREMENTS = 50;

  @Autowired ProcurementRepository repository;
  @Autowired TestEntityManager entityManager;
  @Autowired EntityManagerFactory entityManagerFactory;

  @Test
  void loadingFiftyProcurementsWithThreeHistoryEntriesEachUsesAtMostTwoStatements() {
    List<ProcurementId> ids =
        IntStream.rangeClosed(1, PROCUREMENTS).mapToObj(i -> id(1000 + i)).toList();
    for (var id : ids) {
      var procurement = discovered(snapshot(id));
      procurement.applySnapshot(snapshot(id, 4, "Suspensa"), clockAt(T0.plus(Duration.ofHours(1))));
      procurement.applySnapshot(snapshot(id, 1, "Retomada"), clockAt(T0.plus(Duration.ofHours(2))));
      repository.save(procurement);
    }
    entityManager.flush();
    entityManager.clear();
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    ids.forEach(repository::findById);
    var perIdStatements = statistics.getPrepareStatementCount();
    entityManager.clear();
    statistics.clear();

    var found = repository.findAllById(ids);
    var historyEntries = found.stream().mapToInt(p -> p.history().size()).sum();
    var statements = statistics.getPrepareStatementCount();

    LOG.info(
        "n-plus-one: {} procurements, one findById each = {} statements, one findAllById = {}",
        found.size(),
        perIdStatements,
        statements);
    assertThat(perIdStatements).isEqualTo(2L * PROCUREMENTS);
    assertThat(found).hasSize(PROCUREMENTS).allSatisfy(p -> assertThat(p.history()).hasSize(3));
    assertThat(historyEntries).isEqualTo(PROCUREMENTS * 3);
    assertThat(statements).isLessThanOrEqualTo(2);
  }
}
