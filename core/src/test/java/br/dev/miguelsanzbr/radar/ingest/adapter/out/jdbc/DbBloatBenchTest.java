package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ingest.application.EstimatedValueCentsBackfill;
import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Measures the cost of the expand step on a real Postgres 18 of its own: the backfill per batch,
 * the trigger on the write path and the bloat of {@code ingest.procurement} (one partition) and
 * {@code outbox.outbox_event} after 100 000 rows, after 100 000 updates, after VACUUM and after
 * VACUUM FULL; then the effect of the outbox autovacuum settings during a relay-like run. Run with
 * {@code ./mvnw -Pbench test -pl core -am -Dtest=DbBloatBenchTest}; the tables land in {@code
 * core/target/db-bloat-bench.md} (docs/benchmarks/db). Autovacuum is switched off on the table
 * under test while the bloat is measured, so the numbers show what the updates leave behind and not
 * a race with the daemon; the last experiment leaves it on.
 */
@Tag("bench")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DbBloatBenchTest {

  private static final int ROWS = 100_000;
  private static final int RELAY_BATCH = 100;
  private static final String PARTITION = "ingest.procurement_y2026m10";

  private static PostgreSQLContainer postgres;
  private static JdbcTemplate jdbc;
  private static final StringBuilder REPORT = new StringBuilder();

  @BeforeAll
  static void start() {
    postgres =
        new PostgreSQLContainer("postgres:18")
            .withCommand("postgres", "-c", "autovacuum_naptime=5s", "-c", "max_connections=50");
    postgres.start();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .placeholders(Map.of("readWriterPassword", "bench"))
        .load()
        .migrate();
    jdbc = template();
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple");
    REPORT
        .append("Postgres: ")
        .append(jdbc.queryForObject("select version()", String.class))
        .append("\n\nshared_buffers=")
        .append(jdbc.queryForObject("show shared_buffers", String.class))
        .append(", autovacuum_naptime=")
        .append(jdbc.queryForObject("show autovacuum_naptime", String.class))
        .append(", autovacuum_vacuum_scale_factor (default)=")
        .append(jdbc.queryForObject("show autovacuum_vacuum_scale_factor", String.class))
        .append("\n\n");
  }

  @AfterAll
  static void stop() throws IOException {
    Files.writeString(Path.of("target/db-bloat-bench.md"), REPORT.toString());
    System.out.println(REPORT);
    postgres.stop();
  }

  private static JdbcTemplate template() {
    var ds =
        new SingleConnectionDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
    return new JdbcTemplate(ds);
  }

  private static void loadProcurements(String partition, String publishedAt, boolean preExpand) {
    if (preExpand) {
      jdbc.execute("set session_replication_role = replica");
    }
    jdbc.update(
        """
        insert into %s (numero_controle_pncp, published_at, content_hash, pncp_situation_id,
          pncp_situation_name, status, object_description, organ_cnpj, organ_name, modality_id,
          modality_name, pncp_updated_at, raw, first_seen_at, last_seen_at, estimated_value)
        select 'bl-' || lpad(g::text, 7, '0'), ?::timestamptz, decode(md5(g::text), 'hex'), 1,
          'Divulgada', 'PUBLISHED', repeat(md5(g::text), 8), '83021808000182', 'Orgao de teste', 6,
          'Pregao', now(), jsonb_build_object('n', g, 'objetoCompra', repeat(md5(g::text), 20)),
          now(), now(), mod(g * 37, 100000000) / 100.0
        from generate_series(1, ?) as g
        """
            .formatted(partition),
        publishedAt,
        ROWS);
    jdbc.execute("set session_replication_role = origin");
  }

  private static void updateAll(String partition, boolean preExpand) {
    if (preExpand) {
      jdbc.execute("set session_replication_role = replica");
    }
    jdbc.update("update " + partition + " set last_seen_at = now()");
    jdbc.execute("set session_replication_role = origin");
  }

  private static void loadOutbox(String table) {
    jdbc.update(
        """
        insert into %s (event_id, aggregate_type, aggregate_id, event_type, schema_version,
          payload, headers)
        select gen_random_uuid(), 'Procurement', 'bl-' || mod(g, 20000), 'ProcurementUpdated', 1,
          jsonb_build_object('n', g, 'body', repeat(md5(g::text), 28)), '{}'::jsonb
        from generate_series(1, ?) as g
        """
            .formatted(table),
        ROWS);
  }

  /** The relay's statement, one transaction per batch of ids, as OutboxRelay issues it. */
  private static void relayAll(JdbcTemplate target, String table, long fromId, long toId) {
    for (var id = fromId; id <= toId; id += RELAY_BATCH) {
      target.update(
          "update %s set published_at = now() where id between ? and ?".formatted(table),
          id,
          Math.min(id + RELAY_BATCH - 1, toId));
    }
  }

  private record Bloat(
      String step,
      long tableLen,
      long tuples,
      double deadPercent,
      double freePercent,
      long indexes) {}

  private static Bloat bloat(String step, String table) {
    return jdbc.queryForObject(
        "select ?, table_len, tuple_count, dead_tuple_percent, free_percent,"
            + " pg_indexes_size(?::regclass) from pgstattuple(?)",
        (rs, i) ->
            new Bloat(
                rs.getString(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getDouble(4),
                rs.getDouble(5),
                rs.getLong(6)),
        step,
        table,
        table);
  }

  private static void print(String title, List<Bloat> rows) {
    REPORT.append("### ").append(title).append("\n\n");
    REPORT.append(
        "| step | table_len (MiB) | live tuples | dead_tuple_percent | free_percent | indexes (MiB) |\n");
    REPORT.append("|---|---|---|---|---|---|\n");
    for (var r : rows) {
      REPORT.append(
          "| %s | %.1f | %d | %.2f | %.2f | %.1f |\n"
              .formatted(
                  r.step(),
                  r.tableLen() / 1048576.0,
                  r.tuples(),
                  r.deadPercent(),
                  r.freePercent(),
                  r.indexes() / 1048576.0));
    }
    REPORT.append('\n');
  }

  private static long hotUpdates(String schema, String table) {
    jdbc.execute("select pg_stat_force_next_flush()");
    return jdbc.queryForObject(
        "select n_tup_hot_upd from pg_stat_user_tables where schemaname = ? and relname = ?",
        Long.class,
        schema,
        table);
  }

  private static long millisOf(Runnable action) {
    var start = System.nanoTime();
    action.run();
    return (System.nanoTime() - start) / 1_000_000;
  }

  /** A store that times every batch. */
  private static final class TimedStore implements EstimatedValueCentsStore {

    final EstimatedValueCentsStore delegate;
    final List<Long> micros = new ArrayList<>();

    TimedStore(EstimatedValueCentsStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean hasPending() {
      return delegate.hasPending();
    }

    @Override
    public long pending() {
      return delegate.pending();
    }

    @Override
    public Batch fillBatch(Cursor after, int limit) {
      var start = System.nanoTime();
      var batch = delegate.fillBatch(after, limit);
      micros.add((System.nanoTime() - start) / 1_000);
      return batch;
    }
  }

  private static String backfillRow(int batchSize) {
    jdbc.execute("truncate " + PARTITION);
    loadProcurements(PARTITION, "2026-10-15T12:00:00Z", true);
    var store = new TimedStore(new JdbcEstimatedValueCentsStore(jdbc));
    var total = millisOf(() -> new EstimatedValueCentsBackfill(store, rows -> {}, batchSize).run());
    var sorted = store.micros.stream().sorted().toList();
    var wrong =
        jdbc.queryForObject(
            "select count(*) from "
                + PARTITION
                + " where estimated_value_cents is distinct from round(estimated_value * 100)",
            Long.class);
    assertThat(wrong).isZero();
    return "| %d | %d | %d | %.1f | %.0f | %.1f | %.1f | %.1f |\n"
        .formatted(
            batchSize,
            store.micros.size(),
            total,
            ROWS * 1000.0 / total,
            sorted.getFirst() / 1000.0,
            sorted.get(sorted.size() / 2) / 1000.0,
            sorted.get((int) (sorted.size() * 0.95)) / 1000.0,
            sorted.getLast() / 1000.0);
  }

  @Test
  @Order(1)
  void ingestProcurementBloatAndBackfillTimings() {
    jdbc.execute("alter table ingest.procurement_y2026m10 set (autovacuum_enabled = false)");
    jdbc.execute("alter table ingest.procurement_y2026m11 set (autovacuum_enabled = false)");
    jdbc.execute("alter table ingest.procurement_y2026m12 set (autovacuum_enabled = false)");

    // the write path with and without the trigger (the replica role skips it)
    var withTrigger =
        millisOf(
            () -> loadProcurements("ingest.procurement_y2026m11", "2026-11-15T12:00:00Z", false));
    var withoutTrigger =
        millisOf(
            () -> loadProcurements("ingest.procurement_y2026m12", "2026-12-15T12:00:00Z", true));
    REPORT.append("## ingest.procurement\n\n");
    REPORT.append(
        "Insert of %d rows into one partition: %d ms with the trigger, %d ms without it.\n\n"
            .formatted(ROWS, withTrigger, withoutTrigger));
    // an UPDATE that touches no value column: the trigger still runs per row and compares them
    var updateWithTrigger = millisOf(() -> updateAll("ingest.procurement_y2026m11", false));
    var updateWithoutTrigger = millisOf(() -> updateAll("ingest.procurement_y2026m12", true));
    REPORT.append(
        "UPDATE of %d rows (last_seen_at only): %d ms with the trigger, %d ms without it.\n\n"
            .formatted(ROWS, updateWithTrigger, updateWithoutTrigger));
    jdbc.execute("truncate ingest.procurement_y2026m11, ingest.procurement_y2026m12");

    var steps = new ArrayList<Bloat>();
    loadProcurements(PARTITION, "2026-10-15T12:00:00Z", true);
    steps.add(bloat("after loading " + ROWS + " rows (no cents yet)", PARTITION));

    var store = new TimedStore(new JdbcEstimatedValueCentsStore(jdbc));
    var backfillMs =
        millisOf(() -> new EstimatedValueCentsBackfill(store, rows -> {}, 5_000).run());
    steps.add(bloat("after " + ROWS + " UPDATEs (the backfill, batches of 5000)", PARTITION));
    var hot = hotUpdates("ingest", "procurement_y2026m10");
    jdbc.execute("vacuum " + PARTITION);
    steps.add(bloat("after VACUUM", PARTITION));
    jdbc.execute("vacuum full " + PARTITION);
    steps.add(bloat("after VACUUM FULL", PARTITION));
    print("Bloat of partition y2026m10", steps);
    REPORT.append(
        "Backfill of the bloat run: %d batches, %d ms; HOT updates: %d of %d.\n\n"
            .formatted(store.micros.size(), backfillMs, hot, ROWS));

    var available =
        jdbc.queryForObject(
            "select count(*) from pg_available_extensions where name = 'pg_repack'", Long.class);
    REPORT.append("pg_repack available in the image: %s\n\n".formatted(available > 0));

    REPORT.append("### Backfill per batch size (%d rows, one partition)\n\n".formatted(ROWS));
    REPORT.append(
        "| batch size | batches | total ms | rows/s | min ms | p50 ms | p95 ms | max ms |\n");
    REPORT.append("|---|---|---|---|---|---|---|---|\n");
    for (var size : new int[] {1_000, 5_000, 20_000, ROWS}) {
      REPORT.append(backfillRow(size));
    }
    REPORT.append('\n');
    jdbc.execute("truncate " + PARTITION);
    assertThat(steps).hasSize(4);
  }

  @Test
  @Order(2)
  void outboxBloat() {
    jdbc.execute("alter table outbox.outbox_event set (autovacuum_enabled = false)");
    jdbc.execute("truncate outbox.outbox_event");
    var steps = new ArrayList<Bloat>();
    loadOutbox("outbox.outbox_event");
    steps.add(bloat("after loading " + ROWS + " pending rows", "outbox.outbox_event"));
    var min = jdbc.queryForObject("select min(id) from outbox.outbox_event", Long.class);
    var max = jdbc.queryForObject("select max(id) from outbox.outbox_event", Long.class);
    var relayMs = millisOf(() -> relayAll(jdbc, "outbox.outbox_event", min, max));
    steps.add(
        bloat(
            "after " + ROWS + " UPDATEs (relay marks published, batches of 100)",
            "outbox.outbox_event"));
    var hot = hotUpdates("outbox", "outbox_event");
    jdbc.execute("vacuum outbox.outbox_event");
    steps.add(bloat("after VACUUM", "outbox.outbox_event"));
    jdbc.execute("vacuum full outbox.outbox_event");
    steps.add(bloat("after VACUUM FULL", "outbox.outbox_event"));
    REPORT.append("## outbox.outbox_event\n\n");
    print("Bloat of the table", steps);
    REPORT.append(
        "Relay-like updates: %d ms for %d rows; HOT updates: %d of %d.\n\n"
            .formatted(relayMs, ROWS, hot, ROWS));
    jdbc.execute("alter table outbox.outbox_event reset (autovacuum_enabled)");
    jdbc.execute("truncate outbox.outbox_event");
    assertThat(steps).hasSize(4);
  }

  @Test
  @Order(3)
  void outboxAutovacuumSettingsDuringARelayRun() throws InterruptedException {
    var settings =
        jdbc.queryForObject(
            "select array_to_string(reloptions, ', ') from pg_class where oid = 'outbox.outbox_event'::regclass",
            String.class);
    jdbc.execute("drop table if exists outbox.outbox_event_control");
    jdbc.execute(
        "create table outbox.outbox_event_control (like outbox.outbox_event including all)");
    jdbc.execute(
        "alter table outbox.outbox_event_control reset (autovacuum_vacuum_scale_factor,"
            + " autovacuum_analyze_scale_factor)");
    jdbc.execute("truncate outbox.outbox_event");
    loadOutbox("outbox.outbox_event");
    loadOutbox("outbox.outbox_event_control");
    jdbc.execute("vacuum analyze outbox.outbox_event");
    jdbc.execute("vacuum analyze outbox.outbox_event_control");
    var tables = List.of("outbox_event", "outbox_event_control");
    var min = jdbc.queryForObject("select min(id) from outbox.outbox_event", Long.class);
    var max = jdbc.queryForObject("select max(id) from outbox.outbox_event", Long.class);
    var minControl =
        jdbc.queryForObject("select min(id) from outbox.outbox_event_control", Long.class);

    var base = new long[2];
    for (var i = 0; i < 2; i++) {
      base[i] =
          jdbc.queryForObject(
              "select autovacuum_count from pg_stat_user_tables where schemaname = 'outbox'"
                  + " and relname = ?",
              Long.class,
              tables.get(i));
    }
    var peaks = new long[2];
    var firstVacuumSeconds = new long[] {-1, -1};
    var vacuums = new long[2];
    var start = System.nanoTime();
    var sampling = new boolean[] {true};
    var sampler =
        Thread.ofPlatform()
            .start(
                () -> {
                  var own = template();
                  while (sampling[0]) {
                    for (var i = 0; i < 2; i++) {
                      var row =
                          own.queryForMap(
                              "select n_dead_tup, autovacuum_count from pg_stat_user_tables"
                                  + " where schemaname = 'outbox' and relname = ?",
                              tables.get(i));
                      peaks[i] = Math.max(peaks[i], ((Number) row.get("n_dead_tup")).longValue());
                      vacuums[i] = ((Number) row.get("autovacuum_count")).longValue() - base[i];
                      if (vacuums[i] > 0 && firstVacuumSeconds[i] < 0) {
                        firstVacuumSeconds[i] = (System.nanoTime() - start) / 1_000_000_000;
                      }
                    }
                    try {
                      Thread.sleep(500);
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                });

    // the relay run: batches of 100 as OutboxRelay does, paced like a relay that waits for the
    // broker
    for (var offset = 0L; offset < ROWS; offset += RELAY_BATCH) {
      jdbc.update(
          "update outbox.outbox_event set published_at = now() where id between ? and ?",
          min + offset,
          min + offset + RELAY_BATCH - 1);
      jdbc.update(
          "update outbox.outbox_event_control set published_at = now() where id between ? and ?",
          minControl + offset,
          minControl + offset + RELAY_BATCH - 1);
      Thread.sleep(25);
    }
    var runSeconds = (System.nanoTime() - start) / 1_000_000_000;
    var rowsAtEnd = new ArrayList<String>();
    for (var i = 0; i < 2; i++) {
      rowsAtEnd.add(
          jdbc.queryForObject(
              "select n_dead_tup || ' dead, last_autovacuum=' || coalesce(last_autovacuum::text, 'never')"
                  + " from pg_stat_user_tables where schemaname = 'outbox' and relname = ?",
              String.class,
              tables.get(i)));
    }
    Thread.sleep(20_000);
    sampling[0] = false;
    sampler.join();
    var finals = new ArrayList<Bloat>();
    finals.add(
        bloat("configured table, 20 s after the run, no manual VACUUM", "outbox.outbox_event"));
    finals.add(bloat("control table (defaults), same moment", "outbox.outbox_event_control"));
    REPORT.append(
        "### Autovacuum during a relay run (%d updates in %d s)\n\n".formatted(ROWS, runSeconds));
    REPORT.append("outbox.outbox_event reloptions: `%s`\n\n".formatted(settings));
    REPORT.append(
        "| table | peak n_dead_tup | autovacuum runs | first autovacuum at (s) | n_dead_tup at the end of the run |\n");
    REPORT.append("|---|---|---|---|---|\n");
    for (var i = 0; i < 2; i++) {
      REPORT.append(
          "| %s | %d | %d | %s | %s |\n"
              .formatted(
                  i == 0 ? "outbox_event (0.01 / 0.005)" : "control (0.2 / 0.1)",
                  peaks[i],
                  vacuums[i],
                  firstVacuumSeconds[i] < 0 ? "never" : String.valueOf(firstVacuumSeconds[i]),
                  rowsAtEnd.get(i)));
    }
    REPORT.append('\n');
    print("State after the run", finals);
    jdbc.execute("drop table outbox.outbox_event_control");
    jdbc.execute("truncate outbox.outbox_event");
    assertThat(peaks[0]).isGreaterThan(0);
  }
}
