package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.SharedPostgres;
import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The public reads give up after the configured time; the pool survives the cancelled query. */
class SearchReadTimeoutIT {

  private static HikariDataSource pool() {
    var pool = new HikariDataSource();
    pool.setJdbcUrl(SharedPostgres.INSTANCE.getJdbcUrl());
    pool.setUsername(SharedPostgres.INSTANCE.getUsername());
    pool.setPassword(SharedPostgres.INSTANCE.getPassword());
    pool.setMaximumPoolSize(1);
    return pool;
  }

  @Test
  void aSlowReadIsCancelledAndReportedAsATimeout() {
    try (var pool = pool()) {
      var dsl = new SearchJooqConfiguration().searchReadDsl(pool, Duration.ofSeconds(1));

      assertThatThrownBy(() -> dsl.fetchValue("select pg_sleep(5)"))
          .isInstanceOf(ReadTimeoutException.class);
      assertThat(dsl.fetchValue("select 1")).isEqualTo(1);
    }
  }

  @Test
  void aFastReadIsUntouchedAndOtherErrorsKeepTheirType() {
    try (var pool = pool()) {
      var dsl = new SearchJooqConfiguration().searchReadDsl(pool, Duration.ofMillis(1));

      assertThat(dsl.fetchValue("select 2")).isEqualTo(2);
      assertThatThrownBy(() -> dsl.fetchValue("select * from no_such_table"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .isNotInstanceOf(ReadTimeoutException.class);
    }
  }
}
