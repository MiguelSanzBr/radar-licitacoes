package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

/** Postgres answers a query cancelled by the statement timeout with SQLSTATE 57014. */
final class ReadTimeoutListener implements ExecuteListener {

  private static final String QUERY_CANCELED = "57014";

  @Override
  public void exception(ExecuteContext context) {
    var cause = context.sqlException();
    if (cause != null && QUERY_CANCELED.equals(cause.getSQLState())) {
      context.exception(new ReadTimeoutException());
    }
  }
}
