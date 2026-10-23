package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One statement per batch, so each batch is its own short transaction. The batch is keyed by the
 * primary key (an index scan per partition, merged), not by ctid: ctids repeat across partitions.
 * The update goes through the dual write trigger, which leaves the numeric value as it was.
 */
@Component
class JdbcEstimatedValueCentsStore implements EstimatedValueCentsStore {

  /** The predicate of procurement_cents_pending_idx. */
  private static final String PENDING =
      "estimated_value_cents IS NULL AND estimated_value IS NOT NULL";

  /** Served by procurement_cents_pending_idx (V14), already in key order: no sort. */
  static final String SELECT_BATCH =
      "SELECT numero_controle_pncp, published_at FROM ingest.procurement"
          + " WHERE (numero_controle_pncp, published_at) > (?, ?) AND "
          + PENDING
          + " ORDER BY numero_controle_pncp, published_at LIMIT ?";

  private static final String FILL =
      "WITH batch AS ("
          + SELECT_BATCH
          + "),\n"
          + """
      filled AS (
        UPDATE ingest.procurement p
        SET estimated_value_cents = round(p.estimated_value * 100)
        FROM batch b
        WHERE p.numero_controle_pncp = b.numero_controle_pncp AND p.published_at = b.published_at
        RETURNING p.numero_controle_pncp, p.published_at)
      SELECT count(*) OVER (), numero_controle_pncp, published_at FROM filled
      ORDER BY numero_controle_pncp DESC, published_at DESC
      LIMIT 1
      """;

  private final JdbcTemplate jdbc;

  JdbcEstimatedValueCentsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long pending() {
    // count(*) is never null
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM ingest.procurement WHERE " + PENDING, Long.class));
  }

  @Override
  public boolean hasPending() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM ingest.procurement WHERE " + PENDING + ")",
            Boolean.class));
  }

  @Override
  public Batch fillBatch(Cursor after, int limit) {
    return jdbc
        .query(
            FILL,
            (rs, row) ->
                new Batch(
                    rs.getInt(1),
                    new Cursor(rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant())),
            after.number(),
            OffsetDateTime.ofInstant(after.publishedAt(), ZoneOffset.UTC),
            limit)
        .stream()
        .findFirst()
        .orElseGet(() -> new Batch(0, after));
  }
}
