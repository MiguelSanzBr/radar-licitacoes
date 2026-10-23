package br.dev.miguelsanzbr.radar.ops.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.ops.application.port.PipelineStats;
import br.dev.miguelsanzbr.radar.ops.domain.Checkpoint;
import br.dev.miguelsanzbr.radar.ops.domain.Cursor;
import br.dev.miguelsanzbr.radar.ops.domain.IngestionRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries on tables of other contexts: {@code ingest.ingestion_run}, {@code
 * ingest.ingestion_cursor} and {@code search.projection_checkpoint}. A table is not a Java
 * dependency, so the module rules do not see it; the integration test pins the columns used here,
 * and ADR-0026 records why the operator view may read them directly.
 */
@Repository
class JdbcPipelineStats implements PipelineStats {

  private final JdbcClient jdbc;

  JdbcPipelineStats(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<IngestionRun> lastRuns() {
    return jdbc.sql(
            """
            SELECT DISTINCT ON (kind, modality_id) kind, modality_id, status, fetched, discovered,
                   updated, unchanged, started_at, finished_at
            FROM ingest.ingestion_run
            ORDER BY kind, modality_id, started_at DESC, id DESC
            """)
        .query(
            (rs, row) ->
                new IngestionRun(
                    rs.getString("kind"),
                    rs.getInt("modality_id"),
                    rs.getString("status"),
                    rs.getInt("fetched"),
                    rs.getInt("discovered"),
                    rs.getInt("updated"),
                    rs.getInt("unchanged"),
                    instant(rs, "started_at"),
                    instant(rs, "finished_at")))
        .list();
  }

  @Override
  public List<Cursor> cursors() {
    return jdbc.sql(
            "SELECT modality_id, watermark FROM ingest.ingestion_cursor ORDER BY modality_id")
        .query((rs, row) -> new Cursor(rs.getInt("modality_id"), instant(rs, "watermark")))
        .list();
  }

  @Override
  public List<Checkpoint> checkpoints() {
    return jdbc.sql(
            "SELECT projector, last_offset, updated_at FROM search.projection_checkpoint"
                + " ORDER BY projector")
        .query(
            (rs, row) ->
                new Checkpoint(
                    rs.getString("projector"),
                    rs.getObject("last_offset", Long.class),
                    instant(rs, "updated_at")))
        .list();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
