package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.SharedPostgres;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V14 takes an exclusive lock on ingest.procurement. Behind a long transaction it must give up
 * after its lock timeout instead of queueing, because every later query queues behind it.
 */
class ExpandMigrationLockIT {

  private static final String DATABASE = "expand_lock_it";

  private static String url() {
    var postgres = SharedPostgres.INSTANCE;
    return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + DATABASE);
  }

  private static void admin(String sql) throws SQLException {
    var postgres = SharedPostgres.INSTANCE;
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Flyway flyway(String target) {
    var postgres = SharedPostgres.INSTANCE;
    return Flyway.configure()
        .dataSource(url(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .placeholders(Map.of("readWriterPassword", "lock-it"))
        .target(target)
        .load();
  }

  @Test
  void theExpandGivesUpAtItsLockTimeoutBehindALongTransactionAndAppliesNothing() throws Exception {
    admin("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    admin("CREATE DATABASE " + DATABASE);
    try {
      flyway("13").migrate();
      var postgres = SharedPostgres.INSTANCE;
      try (var holder =
          DriverManager.getConnection(url(), postgres.getUsername(), postgres.getPassword())) {
        holder.setAutoCommit(false);
        holder.createStatement().execute("LOCK TABLE ingest.procurement IN ACCESS SHARE MODE");
        var start = System.nanoTime();

        assertThatThrownBy(() -> flyway("latest").migrate())
            .hasStackTraceContaining("lock timeout");

        var waited = Duration.ofNanos(System.nanoTime() - start);
        assertThat(waited).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(30));
        holder.rollback();
      }
      try (var connection =
              DriverManager.getConnection(
                  url(),
                  SharedPostgres.INSTANCE.getUsername(),
                  SharedPostgres.INSTANCE.getPassword());
          var result =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT count(*) FROM information_schema.columns WHERE table_schema ="
                          + " 'ingest' AND table_name = 'procurement' AND column_name ="
                          + " 'estimated_value_cents'")) {
        result.next();
        assertThat(result.getInt(1)).isZero();
      }
    } finally {
      admin("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    }
  }
}
