package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The contract step ships with this release but Flyway does not run it: it waits in db/contract for
 * the release after the flag has been on. The test runs it inside the test transaction (DDL rolls
 * back in Postgres) to prove it is valid against the expanded schema.
 */
@PostgresJpaTest
class ContractScriptIT {

  private static final String SCRIPT = "db/contract/V17__drop_estimated_value.sql.next";

  @Autowired JdbcTemplate jdbc;

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private void runTheScript() throws IOException {
    jdbc.execute(new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  void flywayDoesNotSeeTheScriptNow() {
    assertThat(new ClassPathResource(SCRIPT).exists()).isTrue();
    assertThat(new ClassPathResource("db/migration/V17__drop_estimated_value.sql").exists())
        .isFalse();
    assertThat(count("select count(*) from flyway_schema_history where version = '17'")).isZero();
  }

  @Test
  void theScriptDropsTheTriggerTheFunctionAndTheNumericColumn() throws IOException {
    runTheScript();

    assertThat(
            count(
                "select count(*) from information_schema.columns where table_schema = 'ingest'"
                    + " and table_name = 'procurement' and column_name = 'estimated_value'"))
        .isZero();
    assertThat(count("select count(*) from pg_trigger where tgname = 'sync_estimated_value'"))
        .isZero();
    assertThat(count("select count(*) from pg_proc where proname = 'sync_estimated_value'"))
        .isZero();
  }

  @Test
  void afterTheScriptTheCentsAreTheOnlyColumnAndPartitionsFollow() throws IOException {
    runTheScript();

    jdbc.update(
        """
        insert into ingest.procurement_y2026m10 (numero_controle_pncp, published_at, content_hash,
          pncp_situation_id, pncp_situation_name, status, object_description, organ_cnpj,
          organ_name, modality_id, modality_name, pncp_updated_at, raw, first_seen_at,
          last_seen_at, estimated_value_cents)
        values ('x', '2026-10-21T12:00:00Z', '\\x00', 1, 's', 'PUBLISHED', 'o', '83021808000182',
          'n', 6, 'm', now(), '{}', now(), now(), 150050)
        """);

    assertThat(count("select estimated_value_cents from ingest.procurement")).isEqualTo(150050L);
  }
}
