package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import java.sql.SQLException;
import org.jooq.ExecuteContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class ReadTimeoutListenerTest {

  private final ReadTimeoutListener listener = new ReadTimeoutListener();
  private final ExecuteContext context = mock(ExecuteContext.class);

  @Test
  void aCancelledQueryBecomesAReadTimeout() {
    when(context.sqlException()).thenReturn(new SQLException("canceling", "57014"));

    listener.exception(context);

    verify(context).exception(ArgumentMatchers.isA(ReadTimeoutException.class));
  }

  @Test
  void anotherSqlStateIsLeftAlone() {
    when(context.sqlException()).thenReturn(new SQLException("syntax", "42601"));

    listener.exception(context);

    verify(context, never()).exception(any(RuntimeException.class));
  }

  @Test
  void aFailureThatIsNotSqlIsLeftAlone() {
    listener.exception(context);

    verify(context, never()).exception(any(RuntimeException.class));
  }
}
