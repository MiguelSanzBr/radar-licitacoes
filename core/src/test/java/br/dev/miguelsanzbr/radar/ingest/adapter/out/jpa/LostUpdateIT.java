package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.discovered;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lost update on {@code ingest.procurement} under READ COMMITTED: two writers on their own
 * connections read the same row, both write, and the first write is lost when the statement ignores
 * {@code version}; with the entity's {@code @Version} the second commit is refused and the retry
 * recovers (docs/benchmarks/db/isolation.md).
 */
@PostgresJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LostUpdateIT {

  private static final Logger LOG = LoggerFactory.getLogger(LostUpdateIT.class);
  private static final int ITERATIONS = 200;
  private static final int FIRST_SEQUENCE = 910_000;
  private static final long WAIT_SECONDS = 10;

  @Autowired DataSource dataSource;
  @Autowired ProcurementRepository repository;
  @Autowired JdbcTemplate jdbc;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void cleanUp() {
    executor.shutdownNow();
    jdbc.update(
        "delete from ingest.procurement_situation_history where numero_controle_pncp like '%-91____/2026'");
    jdbc.update("delete from ingest.procurement where numero_controle_pncp like '%-91____/2026'");
  }

  /** What each writer ended up with, to be compared with the two increments that were issued. */
  private record Outcome(
      long finalRevision, int secondWriterAttempts, boolean secondWriterRefused) {}

  @Test
  void updateThatIgnoresTheVersionLosesTheFirstWriteUnderReadCommitted() throws Exception {
    var lost = 0;
    var raceNanos = 0L;
    for (var i = 0; i < ITERATIONS; i++) {
      var id = id(FIRST_SEQUENCE + i);
      repository.save(discovered(snapshot(id)));
      var before = revisionOf(id);

      var started = System.nanoTime();
      naiveRace(id);
      raceNanos += System.nanoTime() - started;

      if (revisionOf(id) == before + 1) {
        lost++;
      }
    }
    var millis = raceNanos / 1_000_000.0 / ITERATIONS;

    LOG.info(
        "isolation lost-update-naive: {} of {} races lost an increment, {} ms per race",
        lost,
        ITERATIONS,
        "%.1f".formatted(millis));
    assertThat(lost).isEqualTo(ITERATIONS);
  }

  @Test
  void optimisticVersionRefusesTheSecondCommitAndTheRetryKeepsBothWrites() throws Exception {
    var refused = 0;
    var attempts = 0;
    var raceNanos = 0L;
    for (var i = 0; i < ITERATIONS; i++) {
      var id = id(FIRST_SEQUENCE + i);
      repository.save(discovered(snapshot(id)));
      var before = revisionOf(id);

      var started = System.nanoTime();
      var outcome = versionedRace(id);
      raceNanos += System.nanoTime() - started;

      assertThat(outcome.finalRevision()).isEqualTo(before + 2);
      assertThat(historyRows(id)).isEqualTo(3); // discovery, then both changes
      refused += outcome.secondWriterRefused() ? 1 : 0;
      attempts += outcome.secondWriterAttempts();
    }
    var millis = raceNanos / 1_000_000.0 / ITERATIONS;

    LOG.info(
        "isolation lost-update-versioned: {} of {} second commits refused, {} attempts of the"
            + " second writer in total, {} ms per race",
        refused,
        ITERATIONS,
        attempts,
        "%.1f".formatted(millis));
    assertThat(refused).isEqualTo(ITERATIONS);
    assertThat(attempts).isEqualTo(2 * ITERATIONS);
  }

  /** Both read revision r, the first writes r+1 and commits, the second writes r+1 as well. */
  private void naiveRace(ProcurementId id) throws Exception {
    var bothRead = new CyclicBarrier(2);
    var firstCommitted = new CountDownLatch(1);
    Callable<Void> first = () -> naiveWriter(id, bothRead, null, firstCommitted);
    Callable<Void> second = () -> naiveWriter(id, bothRead, firstCommitted, null);
    var a = executor.submit(first);
    var b = executor.submit(second);
    a.get(WAIT_SECONDS, TimeUnit.SECONDS);
    b.get(WAIT_SECONDS, TimeUnit.SECONDS);
  }

  private Void naiveWriter(
      ProcurementId id, CyclicBarrier bothRead, CountDownLatch waitFor, CountDownLatch signal)
      throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      var read = revisionOf(connection, id);
      bothRead.await(WAIT_SECONDS, TimeUnit.SECONDS);
      if (waitFor != null) {
        waitFor.await(WAIT_SECONDS, TimeUnit.SECONDS);
      }
      try (var update =
          connection.prepareStatement(
              "update ingest.procurement set revision = ? where numero_controle_pncp = ?")) {
        update.setLong(1, read + 1);
        update.setString(2, id.value());
        update.executeUpdate();
      }
      connection.commit();
    }
    if (signal != null) {
      signal.countDown();
    }
    return null;
  }

  /** Same race through the repository: the second writer is refused once, then reloads. */
  private Outcome versionedRace(ProcurementId id) throws Exception {
    var bothRead = new CyclicBarrier(2);
    var firstSaved = new CountDownLatch(1);
    var a =
        executor.submit(
            () -> versionedWriter(id, snapshot(id, 4, "Primeiro"), 1, bothRead, null, firstSaved));
    var b =
        executor.submit(
            () -> versionedWriter(id, snapshot(id, 2, "Segundo"), 2, bothRead, firstSaved, null));
    a.get(WAIT_SECONDS, TimeUnit.SECONDS);
    var second = b.get(WAIT_SECONDS, TimeUnit.SECONDS);
    return new Outcome(revisionOf(id), second.attempts(), second.refused());
  }

  private record Writer(int attempts, boolean refused) {}

  private Writer versionedWriter(
      ProcurementId id,
      PncpSnapshot change,
      int hours,
      CyclicBarrier bothRead,
      CountDownLatch waitFor,
      CountDownLatch signal)
      throws Exception {
    var loaded = repository.findById(id).orElseThrow();
    bothRead.await(WAIT_SECONDS, TimeUnit.SECONDS);
    if (waitFor != null) {
      waitFor.await(WAIT_SECONDS, TimeUnit.SECONDS);
    }
    var attempts = 1;
    var refused = false;
    var aggregate = loaded;
    while (true) {
      aggregate.applySnapshot(change, clockAt(T0.plus(Duration.ofHours(hours))));
      try {
        repository.save(aggregate);
        break;
      } catch (ObjectOptimisticLockingFailureException e) {
        refused = true;
      }
      if (attempts == 3) {
        throw new IllegalStateException("retry did not recover");
      }
      attempts++;
      aggregate = repository.findById(id).orElseThrow();
    }
    if (signal != null) {
      signal.countDown();
    }
    return new Writer(attempts, refused);
  }

  private long revisionOf(ProcurementId id) {
    return jdbc.queryForObject(
        "select revision from ingest.procurement where numero_controle_pncp = ?",
        Long.class,
        id.value());
  }

  private long revisionOf(Connection connection, ProcurementId id) throws SQLException {
    try (var select =
        connection.prepareStatement(
            "select revision from ingest.procurement where numero_controle_pncp = ?")) {
      select.setString(1, id.value());
      try (var rs = select.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private int historyRows(ProcurementId id) {
    return jdbc.queryForObject(
        "select count(*) from ingest.procurement_situation_history where numero_controle_pncp = ?",
        Integer.class,
        id.value());
  }
}
