package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.ClusterLock;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * An advisory lock tied to a transaction that stays open on a dedicated connection while the work
 * runs, the same pattern as the outbox relay: the key is a hash of the name, so it needs no
 * registry, and the rollback in {@code finally}, or the death of the process, frees it. The work
 * does not run inside that transaction: it keeps its own short transactions on other connections,
 * so what it commits survives a crash later in the run.
 */
@Component
class PgClusterLock implements ClusterLock {

  private final DataSource dataSource;

  PgClusterLock(@Qualifier("dataSource") DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public boolean tryRun(String name, Runnable work) {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        var locked = lock(connection, name);
        if (locked) {
          work.run();
        }
        return locked;
      } finally {
        connection.rollback();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cluster lock " + name + " failed", e);
    }
  }

  private static boolean lock(Connection connection, String name) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, name);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }
}
