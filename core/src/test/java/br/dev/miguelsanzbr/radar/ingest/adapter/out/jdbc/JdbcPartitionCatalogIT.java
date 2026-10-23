package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.ingest.application.PartitionMaintenance;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The runbook failure mode: a month without a partition. V2 creates partitions up to 2027-12, so
 * the test lives in December 2027 and the next month must come from the job. The test transaction
 * rolls the DDL back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, JdbcPartitionCatalog.class})
class JdbcPartitionCatalogIT {

  private static final Clock DECEMBER_2027 =
      Clock.fixed(Instant.parse("2027-12-15T10:00:00Z"), ZoneOffset.UTC);

  @Autowired JdbcPartitionCatalog catalog;
  @Autowired JdbcTemplate jdbc;

  private String regclass(String name) {
    return jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, "ingest." + name);
  }

  @Test
  void theMigrationLeavesTheMonthAfterItsHorizonWithoutAPartition() {
    assertThat(catalog.exists(YearMonth.of(2027, 12))).isTrue();
    assertThat(catalog.exists(YearMonth.of(2028, 1))).isFalse();
    assertThat(regclass("procurement_y2028m01")).isNull();
  }

  @Test
  void theJobCreatesTheNextMonthsSoTheNextMonthPartitionExists() {
    var job = new PartitionMaintenance(catalog, DECEMBER_2027, 3);
    assertThat(job.monthsAhead()).isZero();

    job.ensure();

    assertThat(regclass("procurement_y2028m01")).isEqualTo("ingest.procurement_y2028m01");
    assertThat(regclass("procurement_y2028m03")).isEqualTo("ingest.procurement_y2028m03");
    assertThat(regclass("procurement_y2028m04")).isNull();
    assertThat(job.monthsAhead()).isEqualTo(3);
  }

  @Test
  void aRowOfTheNewMonthIsAcceptedOnlyAfterTheJobRan() {
    var insert =
        """
        INSERT INTO ingest.procurement (numero_controle_pncp, published_at, content_hash,
          pncp_situation_id, pncp_situation_name, status, object_description, organ_cnpj,
          organ_name, modality_id, modality_name, pncp_updated_at, raw, first_seen_at, last_seen_at)
        VALUES ('00000000000191-1-000001/2028', '2028-01-15T03:00:00Z', '\\x00', 1, 'Divulgada',
          'PUBLISHED', 'objeto', '00000000000191', 'orgao', 6, 'Pregao', '2028-01-15T03:00:00Z',
          '{}', now(), now())
        """;
    jdbc.execute("SAVEPOINT before_insert");
    assertThatThrownBy(() -> jdbc.execute(insert)).hasMessageContaining("no partition of relation");
    jdbc.execute("ROLLBACK TO SAVEPOINT before_insert");

    new PartitionMaintenance(catalog, DECEMBER_2027, 3).ensure();

    assertThat(jdbc.update(insert)).isEqualTo(1);
  }

  @Test
  void ensuringAnExistingMonthIsANoOp() {
    catalog.ensure(YearMonth.of(2027, 12));
    catalog.ensure(YearMonth.of(2027, 12));

    assertThat(catalog.exists(YearMonth.of(2027, 12))).isTrue();
  }
}
