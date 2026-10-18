package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@PostgresJpaTest
class ProcurementPartitionsIT {

  @Autowired JdbcTemplate jdbc;

  private String ensure(String date) {
    return jdbc.queryForObject("select ingest.ensure_partition(date '" + date + "')", String.class);
  }

  private int partitions() {
    return jdbc.queryForObject(
        "select count(*) from pg_inherits where inhparent = 'ingest.procurement'::regclass",
        Integer.class);
  }

  @Test
  void migrationCreatesEveryMonthFrom2024To2027() {
    assertThat(partitions()).isEqualTo(48);
    assertThat(ensure("2024-01-01")).isEqualTo("ingest.procurement_y2024m01");
    assertThat(ensure("2027-12-31")).isEqualTo("ingest.procurement_y2027m12");
  }

  @Test
  void ensurePartitionIsIdempotentAndNamesTheCalendarMonth() {
    var first = ensure("2032-03-17");
    var second = ensure("2032-03-01");

    assertThat(first).isEqualTo("ingest.procurement_y2032m03").isEqualTo(second);
    assertThat(partitions()).isEqualTo(49);
  }

  @Test
  void createdPartitionAcceptsRowsOfItsMonthOnly() {
    ensure("2032-03-01");
    var insert =
        """
        insert into ingest.procurement (numero_controle_pncp, published_at, content_hash,
          pncp_situation_id, pncp_situation_name, status, object_description, organ_cnpj,
          organ_name, modality_id, modality_name, pncp_updated_at, raw, first_seen_at, last_seen_at)
        values ('x', ?::timestamptz, '\\x00', 1, 's', 'PUBLISHED', 'o', '83021808000182', 'n', 6,
          'm', now(), '{}', now(), now())
        """;

    assertThat(jdbc.update(insert, "2032-03-31T23:59:59Z")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select count(*) from ingest.procurement_y2032m03", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void lookupByNumberAloneUsesThePrimaryKeyIndexOfEveryPartition() {
    jdbc.execute("analyze ingest.procurement");
    // The tables are empty here and the planner would pick a sequential scan for them; turning it
    // off shows the primary key (numero_controle_pncp, published_at) can serve the lookup.
    jdbc.execute("set local enable_seqscan = off");

    var plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "explain select * from ingest.procurement where numero_controle_pncp in ('a', 'b')",
                String.class));

    assertThat(plan).contains("procurement_y2026m10_pkey").doesNotContain("Seq Scan");
  }
}
