package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import br.dev.miguelsanzbr.radar.search.application.ReplayLock;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * An advisory lock tied to a transaction that stays open on one pooled connection for the whole
 * replay, the same pattern as the outbox relay: the key is a hash of a fixed string, and the
 * rollback on release, or the death of the process, frees it. It costs one connection of the search
 * pool for the minutes a replay lasts.
 */
@Component
class PgReplayLock implements ReplayLock {

  private static final String TRY_LOCK =
      "SELECT pg_try_advisory_xact_lock(hashtextextended('radar.search.replay', 0))";

  private final DataSource dataSource;

  PgReplayLock(@Qualifier("searchDataSource") DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Optional<Held> tryAcquire() {
    try {
      var connection = dataSource.getConnection();
      var held = false;
      try {
        connection.setAutoCommit(false);
        held = lock(connection);
        return held ? Optional.of(() -> release(connection)) : Optional.empty();
      } finally {
        if (!held) {
          release(connection);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not take the replay lock", e);
    }
  }

  private static boolean lock(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(TRY_LOCK)) {
      result.next();
      return result.getBoolean(1);
    }
  }

  private static void release(Connection connection) {
    try (connection) {
      connection.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException("could not release the replay lock", e);
    }
  }
}
