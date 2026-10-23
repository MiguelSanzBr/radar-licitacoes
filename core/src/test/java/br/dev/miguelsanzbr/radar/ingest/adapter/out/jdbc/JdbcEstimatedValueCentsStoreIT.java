package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.ingest.application.EstimatedValueCentsBackfill;
import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore.Cursor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The backfill against the real partitioned table: thousands of rows over two partitions. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, JdbcEstimatedValueCentsStore.class})
class JdbcEstimatedValueCentsStoreIT {

  private static final int ROWS = 12_000;
  private static final int WITHOUT_VALUE = 1_000;

  @Autowired JdbcEstimatedValueCentsStore store;
  @Autowired JdbcTemplate jdbc;

  /** Rows as they were before the expand step: the trigger stays out of the way. */
  @BeforeEach
  void rowsOfBeforeTheExpandStep() {
    jdbc.execute("set local session_replication_role = replica");
    jdbc.update(
        """
        insert into ingest.procurement (numero_controle_pncp, published_at, content_hash,
          pncp_situation_id, pncp_situation_name, status, object_description, organ_cnpj,
          organ_name, modality_id, modality_name, pncp_updated_at, raw, first_seen_at,
          last_seen_at, estimated_value)
        select 'bf-' || lpad(g::text, 6, '0'),
          case when g % 2 = 0 then timestamptz '2026-09-15T12:00:00Z'
               else timestamptz '2026-10-15T12:00:00Z' end,
          '\\x00', 1, 's', 'PUBLISHED', 'o', '83021808000182', 'n', 6, 'm', now(), '{}', now(),
          now(), case when g <= ? then null else (g * 37 % 100000000) / 100.0 end
        from generate_series(1, ?) as g
        """,
        WITHOUT_VALUE, ROWS);
    jdbc.execute("set local session_replication_role = origin");
  }

  private long count(String where) {
    return jdbc.queryForObject(
        "select count(*) from ingest.procurement where numero_controle_pncp like 'bf-%' and "
            + where,
        Long.class);
  }

  @Test
  void pendingCountsTheRowsWithAValueAndNoCents() {
    assertThat(store.pending()).isEqualTo(ROWS - WITHOUT_VALUE);
  }

  @Test
  void hasPendingSaysWhetherAnyRowStillWaits() {
    assertThat(store.hasPending()).isTrue();

    new EstimatedValueCentsBackfill(store, rows -> {}, 5_000).run();

    assertThat(store.hasPending()).isFalse();
  }

  @Test
  void theBatchIsPickedFromThePartialIndexInKeyOrderWithoutASort() {
    jdbc.execute("analyze ingest.procurement");

    var plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "explain " + JdbcEstimatedValueCentsStore.SELECT_BATCH,
                String.class,
                Cursor.START.number(),
                OffsetDateTime.ofInstant(Cursor.START.publishedAt(), ZoneOffset.UTC),
                5_000));

    // one index scan per partition merged in key order: no Sort node, no Seq Scan
    assertThat(plan)
        .contains("Merge Append", "Index Only Scan")
        .doesNotContain("-> Sort", "->  Sort", "Seq Scan");
  }

  @Test
  void theRunFillsEveryRowOfBothPartitionsAndNothingElse() {
    var seen = new ArrayList<Long>();
    var job = new EstimatedValueCentsBackfill(store, seen::add, 5_000);

    var filled = job.run();

    assertThat(filled).isEqualTo(ROWS - WITHOUT_VALUE);
    assertThat(seen).last().isEqualTo(0L);
    assertThat(store.pending()).isZero();
    assertThat(count("estimated_value_cents is distinct from round(estimated_value * 100)"))
        .isZero();
    assertThat(count("estimated_value is null and estimated_value_cents is null"))
        .isEqualTo(WITHOUT_VALUE);
    assertThat(
            jdbc.queryForList(
                "select distinct tableoid::regclass::text from ingest.procurement where"
                    + " numero_controle_pncp like 'bf-%' order by 1",
                String.class))
        .containsExactly("ingest.procurement_y2026m09", "ingest.procurement_y2026m10");
  }

  @Test
  void aBatchStopsAtTheLimitAndReturnsTheLastKeyItFilled() {
    var batch = store.fillBatch(Cursor.START, 2_000);

    assertThat(batch.rows()).isEqualTo(2_000);
    assertThat(batch.last().number()).isEqualTo("bf-003000");
    assertThat(batch.last().publishedAt()).isEqualTo(Instant.parse("2026-09-15T12:00:00Z"));
    assertThat(store.pending()).isEqualTo(ROWS - WITHOUT_VALUE - 2_000);
  }

  @Test
  void aBatchAfterTheLastKeyFillsNothingAndKeepsTheCursor() {
    var after = new Cursor("bf-999999", Instant.parse("2026-10-15T12:00:00Z"));

    var batch = store.fillBatch(after, 100);

    assertThat(batch.rows()).isZero();
    assertThat(batch.last()).isEqualTo(after);
  }

  @Test
  void theCursorIsTheWholeKeySoTwoNoticesOfTheSameNumberAreBothReached() {
    jdbc.execute("set local session_replication_role = replica");
    for (var month : List.of("2026-08-10T12:00:00Z", "2026-11-10T12:00:00Z")) {
      jdbc.update(
          "insert into ingest.procurement (numero_controle_pncp, published_at, content_hash,"
              + " pncp_situation_id, pncp_situation_name, status, object_description, organ_cnpj,"
              + " organ_name, modality_id, modality_name, pncp_updated_at, raw, first_seen_at,"
              + " last_seen_at, estimated_value) values ('zz-same', ?::timestamptz, '\\x00', 1,"
              + " 's', 'PUBLISHED', 'o', '83021808000182', 'n', 6, 'm', now(), '{}', now(), now(),"
              + " 5.55)",
          month);
    }
    jdbc.execute("set local session_replication_role = origin");

    new EstimatedValueCentsBackfill(store, rows -> {}, 1).run();

    assertThat(
            jdbc.queryForObject(
                "select count(*) from ingest.procurement where numero_controle_pncp = 'zz-same'"
                    + " and estimated_value_cents = 555",
                Long.class))
        .isEqualTo(2L);
  }
}
