package br.dev.miguelsanzbr.radar.db;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery of an alert is inserted once per (search, procurement). The real table gets that
 * from the primary key and {@code ON CONFLICT DO NOTHING}; the alternative is a copy of the table
 * without the key, written under SERIALIZABLE with a retry on SQLSTATE 40001
 * (docs/benchmarks/db/isolation.md).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SerializableDeliveryIT {

  private static final Logger LOG = LoggerFactory.getLogger(SerializableDeliveryIT.class);
  private static final int ITERATIONS = 200;
  private static final int MAX_ATTEMPTS = 20;
  private static final long WAIT_SECONDS = 10;
  private static final String SERIALIZATION_FAILURE = "40001";

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final UUID search = UUID.randomUUID();
  private final UUID subscriber = UUID.randomUUID();
  private final AtomicInteger refusedAtStatement = new AtomicInteger();
  private final AtomicInteger refusedAtCommit = new AtomicInteger();

  @BeforeEach
  void setUp() {
    jdbc.execute("create schema if not exists iso_test");
    jdbc.execute("drop table if exists iso_test.alert_delivery_copy");
    // no INCLUDING INDEXES: the copy has neither the primary key nor any unique constraint
    jdbc.execute(
        "create table iso_test.alert_delivery_copy (like alerts.alert_delivery including defaults)");
    jdbc.update(
        "insert into alerts.subscriber (id, consent_at, created_at) values (?, now(), now())",
        subscriber);
    jdbc.update(
        "insert into alerts.saved_search (id, subscriber_id, name, query_text, frequency, created_at)"
            + " values (?, ?, 'iso', 'iso', 'INSTANT', now())",
        search,
        subscriber);
  }

  @AfterEach
  void cleanUp() {
    executor.shutdownNow();
    jdbc.update("delete from alerts.alert_delivery where saved_search_id = ?", search);
    jdbc.update("delete from alerts.saved_search where id = ?", search);
    jdbc.update("delete from alerts.subscriber where id = ?", subscriber);
    jdbc.execute("drop schema iso_test cascade");
  }

  /** One writer's account of a race: transactions used, whether it inserted, whether it gave up. */
  private record Result(int attempts, boolean inserted, boolean gaveUp) {}

  /** Totals of 200 races of two writers. */
  private record Totals(int attempts, int failures, int bothRefused, int gaveUp) {}

  @Test
  void serializableWithJitteredRetryKeepsExactlyOneRowWhereTheCopyHasNoKey() throws Exception {
    var millis = new ArrayList<Double>();

    var totals = serializableRaces("ISO-SER-", true, millis);

    LOG.info(
        "isolation serializable-copy with jitter: {} races, {}, refused at statement {} at commit {},"
            + " {}",
        ITERATIONS,
        totals,
        refusedAtStatement.get(),
        refusedAtCommit.get(),
        summary(millis));
    assertThat(totals.gaveUp()).isZero();
    assertThat(totals.failures()).isGreaterThanOrEqualTo(ITERATIONS);
  }

  /** Same retry without the pause: the invariant holds, but the writers can retry in lockstep. */
  @Test
  void serializableWithImmediateRetryNeverDuplicatesButMayExhaustTheAttempts() throws Exception {
    var millis = new ArrayList<Double>();

    var totals = serializableRaces("ISO-NOJ-", false, millis);

    LOG.info(
        "isolation serializable-copy without jitter: {} races, {}, {}",
        ITERATIONS,
        totals,
        summary(millis));
    assertThat(totals.failures()).isGreaterThanOrEqualTo(ITERATIONS - totals.gaveUp() * 2);
  }

  private Totals serializableRaces(String prefix, boolean jitter, List<Double> millis)
      throws Exception {
    var attempts = 0;
    var failures = 0;
    var bothRefused = 0;
    var gaveUp = 0;
    for (var i = 0; i < ITERATIONS; i++) {
      var procurement = prefix + "%03d".formatted(i);
      var started = System.nanoTime();

      var results = race(barrier -> serializableInsert(procurement, barrier, jitter));

      millis.add((System.nanoTime() - started) / 1_000_000.0);
      var rows = count("iso_test.alert_delivery_copy", procurement);
      var gaveUpHere = results.stream().filter(Result::gaveUp).count();
      assertThat(rows).as("never a duplicate").isLessThanOrEqualTo(1);
      if (jitter) {
        assertThat(rows).isEqualTo(1);
        assertThat(results.stream().filter(Result::inserted)).hasSize(1);
      }
      var raceAttempts = results.stream().mapToInt(Result::attempts).sum();
      attempts += raceAttempts;
      // each attempt beyond the one per writer is a retry after SQLSTATE 40001
      failures += raceAttempts - 2;
      bothRefused += raceAttempts > 3 ? 1 : 0;
      gaveUp += (int) gaveUpHere;
    }
    return new Totals(attempts, failures, bothRefused, gaveUp);
  }

  @Test
  void primaryKeyWithOnConflictGivesTheSameResultWithoutRetries() throws Exception {
    var attempts = 0;
    var millis = new ArrayList<Double>();
    for (var i = 0; i < ITERATIONS; i++) {
      var procurement = "ISO-PK-%03d".formatted(i);
      var started = System.nanoTime();

      var results = race(barrier -> onConflictInsert(procurement, barrier));

      millis.add((System.nanoTime() - started) / 1_000_000.0);
      assertThat(count("alerts.alert_delivery", procurement)).isEqualTo(1);
      assertThat(results.stream().filter(Result::inserted)).hasSize(1);
      attempts += results.stream().mapToInt(Result::attempts).sum();
    }

    LOG.info(
        "isolation on-conflict: {} races, {} attempts in total, {}",
        ITERATIONS,
        attempts,
        summary(millis));
    assertThat(attempts).isEqualTo(2 * ITERATIONS);
  }

  private interface Writer {
    Result run(CyclicBarrier barrier) throws Exception;
  }

  private List<Result> race(Writer writer) throws Exception {
    var barrier = new CyclicBarrier(2);
    Callable<Result> task = () -> writer.run(barrier);
    var a = executor.submit(task);
    var b = executor.submit(task);
    return List.of(a.get(WAIT_SECONDS, TimeUnit.SECONDS), b.get(WAIT_SECONDS, TimeUnit.SECONDS));
  }

  /**
   * Insert unless a row exists. The first attempt waits for the other writer after the statement,
   * so both have read "no row" before either commits; a serialization failure (at the statement or
   * at commit) rolls back and starts over, and a later attempt sees the winner's row.
   */
  private Result serializableInsert(String procurement, CyclicBarrier barrier, boolean jitter)
      throws Exception {
    for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        var committing = false;
        try {
          var inserted =
              update(
                      connection,
                      "insert into iso_test.alert_delivery_copy (saved_search_id, procurement_id,"
                          + " matched_at) select ?, ?, now() where not exists (select 1 from"
                          + " iso_test.alert_delivery_copy where saved_search_id = ? and"
                          + " procurement_id = ?)",
                      search,
                      procurement,
                      search,
                      procurement)
                  == 1;
          if (attempt == 1) {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
          }
          committing = true;
          connection.commit();
          return new Result(attempt, inserted, false);
        } catch (SQLException e) {
          if (!SERIALIZATION_FAILURE.equals(e.getSQLState())) {
            throw e;
          }
          (committing ? refusedAtCommit : refusedAtStatement).incrementAndGet();
          connection.rollback();
        }
      }
      if (jitter) {
        Thread.sleep(ThreadLocalRandom.current().nextLong(0, 4L << attempt));
      }
    }
    return new Result(MAX_ATTEMPTS, false, true);
  }

  /** Both writers start together; the second waits on the first one's row lock, then skips. */
  private Result onConflictInsert(String procurement, CyclicBarrier barrier) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
      var inserted =
          update(
                  connection,
                  "insert into alerts.alert_delivery (saved_search_id, procurement_id, matched_at)"
                      + " values (?, ?, now()) on conflict do nothing",
                  search,
                  procurement)
              == 1;
      connection.commit();
      return new Result(1, inserted, false);
    }
  }

  private int update(Connection connection, String sql, Object... parameters) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (var i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      return statement.executeUpdate();
    }
  }

  private int count(String table, String procurement) {
    return jdbc.queryForObject(
        "select count(*) from " + table + " where saved_search_id = ? and procurement_id = ?",
        Integer.class,
        search,
        procurement);
  }

  private static String summary(List<Double> millis) {
    var sorted = new ArrayList<>(millis);
    Collections.sort(sorted);
    return "mean %.2f ms, p50 %.2f ms, p95 %.2f ms, max %.2f ms"
        .formatted(
            sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
            sorted.get(sorted.size() / 2),
            sorted.get((int) (sorted.size() * 0.95)),
            sorted.getLast());
  }
}
