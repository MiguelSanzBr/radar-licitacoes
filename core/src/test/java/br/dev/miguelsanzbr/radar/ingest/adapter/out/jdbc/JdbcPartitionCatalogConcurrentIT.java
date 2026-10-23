package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two replicas run the daily job at the same time. Each call commits on its own connection, so the
 * test is outside a transaction and drops the far-future months it created.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, JdbcPartitionCatalog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcPartitionCatalogConcurrentIT {

  private static final int ROUNDS = 12;
  private static final YearMonth FIRST = YearMonth.of(2099, 1);

  @Autowired JdbcPartitionCatalog catalog;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void dropTheMonthsOfTheTest() {
    for (var i = 0; i < ROUNDS; i++) {
      var month = FIRST.plusMonths(i);
      jdbc.execute(
          "DROP TABLE IF EXISTS ingest.procurement_y%04dm%02d"
              .formatted(month.getYear(), month.getMonthValue()));
    }
  }

  @Test
  void twoConcurrentEnsuresOfANewMonthBothSucceed() throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (var i = 0; i < ROUNDS; i++) {
        var month = FIRST.plusMonths(i);
        var barrier = new CyclicBarrier(2);
        var calls =
            IntStream.range(0, 2)
                .mapToObj(
                    n ->
                        pool.submit(
                            () -> {
                              barrier.await(10, TimeUnit.SECONDS);
                              catalog.ensure(month);
                              return null;
                            }))
                .toList();
        for (var call : List.copyOf(calls)) {
          call.get(30, TimeUnit.SECONDS);
        }
        assertThat(catalog.exists(month)).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
