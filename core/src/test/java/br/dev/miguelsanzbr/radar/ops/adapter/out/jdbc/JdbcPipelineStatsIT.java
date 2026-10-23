package br.dev.miguelsanzbr.radar.ops.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.ops.application.port.PipelineStats;
import br.dev.miguelsanzbr.radar.ops.domain.Checkpoint;
import br.dev.miguelsanzbr.radar.ops.domain.Cursor;
import br.dev.miguelsanzbr.radar.ops.domain.IngestionRun;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcPipelineStatsIT extends PostgresIT {

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");

  @Autowired PipelineStats stats;

  @BeforeEach
  void emptyTables() {
    jdbc.sql("TRUNCATE ingest.ingestion_run, ingest.ingestion_cursor, search.projection_checkpoint")
        .update();
  }

  private void run(String kind, int modality, String status, Instant started, Instant finished) {
    jdbc.sql(
            """
            INSERT INTO ingest.ingestion_run (kind, modality_id, window_start, window_end, status,
              fetched, discovered, updated, unchanged, started_at, finished_at)
            VALUES (:kind, :modality, :started, :started, :status, 10, 2, 3, 5, :started, :finished)
            """)
        .param("kind", kind)
        .param("modality", modality)
        .param("status", status)
        .param("started", java.sql.Timestamp.from(started))
        .param(
            "finished",
            finished == null ? null : java.sql.Timestamp.from(finished),
            java.sql.Types.TIMESTAMP)
        .update();
  }

  @Test
  void theLastRunOfEachKindAndModalityIsListedWithItsCounters() {
    run("INCREMENTAL", 6, "SUCCEEDED", AT.minusSeconds(900), AT.minusSeconds(880));
    run("INCREMENTAL", 6, "FAILED", AT.minusSeconds(60), AT);
    run("INCREMENTAL", 12, "RUNNING", AT.minusSeconds(30), null);
    run("BACKFILL", 6, "SUCCEEDED", AT.minusSeconds(3600), AT.minusSeconds(3000));

    var runs = stats.lastRuns();

    assertThat(runs)
        .containsExactly(
            new IngestionRun(
                "BACKFILL",
                6,
                "SUCCEEDED",
                10,
                2,
                3,
                5,
                AT.minusSeconds(3600),
                AT.minusSeconds(3000)),
            new IngestionRun("INCREMENTAL", 6, "FAILED", 10, 2, 3, 5, AT.minusSeconds(60), AT),
            new IngestionRun("INCREMENTAL", 12, "RUNNING", 10, 2, 3, 5, AT.minusSeconds(30), null));
  }

  @Test
  void theCursorWatermarksAreListedByModality() {
    jdbc.sql(
            "INSERT INTO ingest.ingestion_cursor (modality_id, watermark, updated_at) VALUES"
                + " (12, :w, :w), (6, :w, :w)")
        .param("w", java.sql.Timestamp.from(AT))
        .update();

    assertThat(stats.cursors()).containsExactly(new Cursor(6, AT), new Cursor(12, AT));
  }

  @Test
  void theProjectionCheckpointsAreListedByProjector() {
    jdbc.sql(
            "INSERT INTO search.projection_checkpoint (projector, last_event_id, last_offset,"
                + " updated_at) VALUES ('search', :id, 42, :at), ('detail', NULL, NULL, :at)")
        .param("id", UUID.randomUUID())
        .param("at", java.sql.Timestamp.from(AT))
        .update();

    assertThat(stats.checkpoints())
        .containsExactly(new Checkpoint("detail", null, AT), new Checkpoint("search", 42L, AT));
  }

  @Test
  void emptyTablesGiveEmptyLists() {
    assertThat(stats.lastRuns()).isEmpty();
    assertThat(stats.cursors()).isEmpty();
    assertThat(stats.checkpoints()).isEmpty();
  }
}
