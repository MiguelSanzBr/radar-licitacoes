package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The failure and refusal paths the real database does not produce on demand. */
class PgClusterLockTest {

  private final DataSource dataSource = mock();
  private final Connection connection = mock();
  private final PreparedStatement statement = mock();
  private final ResultSet result = mock();

  private PgClusterLock lock() throws SQLException {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    return new PgClusterLock(dataSource);
  }

  @Test
  void aRefusedLockDoesNotRunTheWorkAndReleasesTheConnection() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(false);
    var ran = new AtomicBoolean();

    assertThat(lock.tryRun("x", () -> ran.set(true))).isFalse();

    assertThat(ran).isFalse();
    var order = inOrder(connection);
    order.verify(connection).rollback();
    order.verify(connection).close();
  }

  @Test
  void theLockIsReleasedWhenTheWorkFails() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(true);

    assertThatThrownBy(
            () ->
                lock.tryRun(
                    "x",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .hasMessage("boom");

    verify(connection).rollback();
    verify(connection).close();
  }

  @Test
  void aConnectionThatCannotBeOpenedIsReported() throws Exception {
    var lock = lock();
    when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

    assertThatThrownBy(() -> lock.tryRun("x", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void aReleaseThatFailsIsReportedAndTheConnectionStillCloses() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(true);
    doThrow(new SQLException("gone")).when(connection).rollback();

    assertThatThrownBy(() -> lock.tryRun("x", () -> {})).isInstanceOf(IllegalStateException.class);

    verify(connection).close();
  }

  @Test
  void aCloseThatFailsAfterTheWorkFailedKeepsTheWorkFailure() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(true);
    doThrow(new SQLException("close")).when(connection).close();

    assertThatThrownBy(
            () ->
                lock.tryRun(
                    "x",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .hasMessage("boom")
        .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
  }

  @Test
  void aCloseThatFailsAfterASuccessfulRunIsReported() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(true);
    doThrow(new SQLException("close")).when(connection).close();

    assertThatThrownBy(() -> lock.tryRun("x", () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }
}
