package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@OutboxSpringBootTest
class SearchDataSourceIT {

  @Autowired
  @Qualifier("searchDsl")
  DSLContext searchDsl;

  @Autowired DataSource primary;
  @Autowired PlatformTransactionManager primaryTransactions;

  @Autowired
  @Qualifier("searchTransactionManager")
  PlatformTransactionManager searchTransactions;

  @Test
  void searchDslConnectsAsTheReadWriterRole() {
    assertThat(searchDsl.fetchValue("select current_user")).isEqualTo("radar_read_writer");
  }

  @Test
  void theJpaSideKeepsTheOwnerConnectionAndTheJpaTransactionManager() {
    assertThat(new JdbcTemplate(primary).queryForObject("select current_user", String.class))
        .isNotEqualTo("radar_read_writer");
    assertThat(primaryTransactions).isInstanceOf(JpaTransactionManager.class);
  }

  @Test
  void searchWritesJoinTheSearchTransactionAndRollBackWithIt() {
    var transaction = new TransactionTemplate(searchTransactions);
    transaction.executeWithoutResult(
        status -> {
          searchDsl.execute(
              "INSERT INTO search.projection_checkpoint (projector, updated_at) VALUES ('it', now())");
          status.setRollbackOnly();
        });

    assertThat(
            searchDsl.fetchCount(
                DSL.table("search.projection_checkpoint"), DSL.field("projector").eq("it")))
        .isZero();
  }
}
