package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionCursorRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionCursor;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Plain JDBC: one row per modality in {@code ingest.ingestion_cursor}. */
@Component
class JdbcIngestionCursorRepository implements IngestionCursorRepository {

  private final JdbcTemplate jdbc;

  JdbcIngestionCursorRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<IngestionCursor> find(int modalityId) {
    return jdbc
        .query(
            """
            SELECT watermark, extract(epoch FROM overlap)::bigint AS overlap_seconds, updated_at
            FROM ingest.ingestion_cursor WHERE modality_id = ?
            """,
            (rs, row) ->
                new IngestionCursor(
                    modalityId,
                    rs.getObject("watermark", OffsetDateTime.class).toInstant(),
                    Duration.ofSeconds(rs.getLong("overlap_seconds")),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
            modalityId)
        .stream()
        .findFirst();
  }

  @Override
  public void save(IngestionCursor cursor) {
    jdbc.update(
        """
        INSERT INTO ingest.ingestion_cursor (modality_id, watermark, overlap, updated_at)
        VALUES (?, ?, make_interval(secs => ?), ?)
        ON CONFLICT (modality_id) DO UPDATE
          SET watermark = EXCLUDED.watermark, overlap = EXCLUDED.overlap,
              updated_at = EXCLUDED.updated_at
        """,
        cursor.modalityId(),
        OffsetDateTime.ofInstant(cursor.watermark(), ZoneOffset.UTC),
        cursor.overlap().toSeconds(),
        OffsetDateTime.ofInstant(cursor.updatedAt(), ZoneOffset.UTC));
  }
}
