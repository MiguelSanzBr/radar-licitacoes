package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.discovered;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transactions: every save commits, so the rows are removed after each test. */
@PostgresJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcurementConcurrencyIT {

  @Autowired ProcurementRepository repository;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @AfterEach
  void cleanUp() {
    executor.shutdownNow();
    jdbc.update(
        "delete from ingest.procurement_situation_history where numero_controle_pncp like '%-0090__/2026'");
    jdbc.update("delete from ingest.procurement where numero_controle_pncp like '%-0090__/2026'");
  }

  @Test
  void staleSaveFromAnotherTransactionFailsWithOptimisticLock() {
    repository.save(discovered(snapshot(id(9001))));
    var first = repository.findById(id(9001)).orElseThrow();
    var second = repository.findById(id(9001)).orElseThrow();
    first.applySnapshot(snapshot(id(9001), 4, "Primeiro"), clockAt(T0.plus(Duration.ofHours(1))));
    second.applySnapshot(snapshot(id(9001), 2, "Segundo"), clockAt(T0.plus(Duration.ofHours(2))));
    repository.save(first);

    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(repository.findById(id(9001)).orElseThrow().objectDescription())
        .isEqualTo("Primeiro");
  }

  @Test
  void heartbeatDoesNotInvalidateAConcurrentWriter() {
    var snapshot = snapshot(id(9002));
    repository.save(discovered(snapshot));
    var heartbeat = repository.findById(id(9002)).orElseThrow();
    var writer = repository.findById(id(9002)).orElseThrow();
    heartbeat.applySnapshot(snapshot, clockAt(T0.plus(Duration.ofHours(1))));
    writer.applySnapshot(snapshot(id(9002), 4, "Real"), clockAt(T0.plus(Duration.ofHours(2))));

    repository.save(heartbeat);
    var saved = repository.save(writer);

    assertThat(saved.version()).isEqualTo(1);
  }

  @Test
  void concurrentFirstDiscoveryLosesWithOptimisticLockFailure() throws Exception {
    var procurement = discovered(snapshot(id(9003)));
    var loser = new AtomicReference<Future<Procurement>>();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              repository.save(procurement);
              loser.set(executor.submit(() -> repository.save(procurement)));
              awaitSomeoneBlockedOnALock();
            });

    assertThatThrownBy(() -> loser.get().get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(OptimisticLockingFailureException.class);
  }

  /** The loser inserts the same primary key and waits for the winner's commit. */
  private void awaitSomeoneBlockedOnALock() {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var blocked =
          jdbc.queryForObject(
              "select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
              Integer.class);
      if (blocked > 0) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("the second writer never blocked on the first insert");
  }
}
