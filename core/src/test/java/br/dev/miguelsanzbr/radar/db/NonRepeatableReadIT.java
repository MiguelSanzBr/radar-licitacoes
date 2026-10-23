package br.dev.miguelsanzbr.radar.db;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Non-repeatable read in a stats-like read: one transaction reads a count and a sum over {@code
 * search.procurement_search_view} for a UF twice while another transaction commits a change in
 * between. READ COMMITTED sees two different answers, REPEATABLE READ sees the same one
 * (docs/benchmarks/db/isolation.md).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NonRepeatableReadIT {

  private static final Logger LOG = LoggerFactory.getLogger(NonRepeatableReadIT.class);
  private static final String UF = "ZZ";
  private static final int ROWS = 10;
  private static final long WAIT_SECONDS = 10;

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @BeforeEach
  void seed() {
    for (var i = 0; i < ROWS; i++) {
      insert("ISO-NRR-%02d".formatted(i), "100.00");
    }
  }

  @AfterEach
  void cleanUp() {
    executor.shutdownNow();
    jdbc.update("delete from search.procurement_search_view where uf = ?", UF);
  }

  private record Stats(long count, BigDecimal sum) {}

  @ParameterizedTest
  @ValueSource(
      ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ})
  void aCommitBetweenTwoReadsShowsOnlyUnderReadCommitted(int isolation) throws Exception {
    var firstReadDone = new CountDownLatch(1);
    var writerCommitted = new CountDownLatch(1);
    var writer =
        executor.submit(
            () -> {
              firstReadDone.await(WAIT_SECONDS, TimeUnit.SECONDS);
              insert("ISO-NRR-NEW", "50.00");
              jdbc.update(
                  "update search.procurement_search_view set estimated_value = 200.00"
                      + " where numero_controle_pncp = 'ISO-NRR-00'");
              writerCommitted.countDown();
              return null;
            });

    Stats first;
    Stats second;
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(isolation);
      first = stats(connection);
      firstReadDone.countDown();
      assertThat(writerCommitted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      second = stats(connection);
      connection.commit();
    }
    writer.get(WAIT_SECONDS, TimeUnit.SECONDS);

    LOG.info(
        "isolation non-repeatable-read level={}: first={} second={}", isolation, first, second);
    assertThat(first).isEqualTo(new Stats(ROWS, new BigDecimal("1000.00")));
    if (isolation == Connection.TRANSACTION_READ_COMMITTED) {
      assertThat(second).isEqualTo(new Stats(ROWS + 1, new BigDecimal("1150.00")));
    } else {
      assertThat(second).isEqualTo(first);
    }
  }

  private Stats stats(Connection connection) throws SQLException {
    try (var select =
        connection.prepareStatement(
            "select count(*), sum(estimated_value) from search.procurement_search_view"
                + " where uf = ?"); ) {
      select.setString(1, UF);
      try (var rs = select.executeQuery()) {
        rs.next();
        return new Stats(rs.getLong(1), rs.getBigDecimal(2));
      }
    }
  }

  private void insert(String number, String value) {
    jdbc.update(
        """
        insert into search.procurement_search_view
          (numero_controle_pncp, search_vector, object_description, organ_name, uf, modality_id,
           status, estimated_value, value_band, published_at, pncp_updated_at, source_version,
           projected_at, source)
        values (?, to_tsvector('simple', 'isolation'), 'isolation', 'organ', ?, 6, 'PUBLISHED',
                ?::numeric, 1, now(), now(), 1, now(), 'synthetic')
        """,
        number,
        UF,
        value);
  }
}
