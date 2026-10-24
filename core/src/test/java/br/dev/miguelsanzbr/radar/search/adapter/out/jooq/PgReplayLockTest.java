package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The failure paths the real database does not produce. */
class PgReplayLockTest {

  private final DataSource dataSource = mock();
  private final Connection connection = mock();
  private final Statement statement = mock();
  private final ResultSet result = mock();

  private PgReplayLock lock() throws SQLException {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery(anyString())).thenReturn(result);
    return new PgReplayLock(dataSource);
  }

  @Test
  void aConnectionThatCannotBeOpenedIsReported() throws Exception {
    var lock = lock();
    when(dataSource.getConnection()).thenThrow(new SQLException("pool exhausted"));

    assertThatThrownBy(lock::tryAcquire)
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void aFailedLockQueryClosesTheConnection() throws Exception {
    var lock = lock();
    when(statement.executeQuery(anyString())).thenThrow(new SQLException("boom"));

    assertThatThrownBy(lock::tryAcquire).isInstanceOf(IllegalStateException.class);

    verify(connection).close();
  }

  @Test
  void aRefusedLockClosesTheConnectionAndAGrantedOneKeepsItUntilReleased() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(false, true);

    assertThat(lock.tryAcquire()).isEmpty();
    verify(connection).close();

    var held = lock.tryAcquire().orElseThrow();
    var order = inOrder(connection);
    order.verify(connection).setAutoCommit(false);
    held.close();
    order.verify(connection).rollback();
    order.verify(connection).close();
  }

  @Test
  void aReleaseThatFailsIsReportedButTheConnectionIsStillClosed() throws Exception {
    var lock = lock();
    when(result.getBoolean(1)).thenReturn(true);
    var held = lock.tryAcquire().orElseThrow();
    doThrow(new SQLException("gone")).when(connection).rollback();

    assertThatThrownBy(held::close).isInstanceOf(IllegalStateException.class);

    verify(connection).close();
  }
}
