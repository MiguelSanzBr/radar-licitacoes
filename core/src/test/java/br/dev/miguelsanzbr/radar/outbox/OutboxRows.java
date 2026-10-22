package br.dev.miguelsanzbr.radar.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Direct inserts into the outbox table, so the generic module is tested without any context. */
public final class OutboxRows {

  private OutboxRows() {}

  public static long insert(JdbcTemplate jdbc, String aggregateId, String eventType) {
    return jdbc.queryForObject(
        """
        INSERT INTO outbox.outbox_event
          (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers)
        VALUES (?, 'Procurement', ?, ?, 1, '{"k":1}'::jsonb, '{"eventType":"x","schemaVersion":1}'::jsonb)
        RETURNING id
        """,
        Long.class,
        UUID.randomUUID(),
        aggregateId,
        eventType);
  }

  /** Inserts with the given headers JSON and returns the generated event id. */
  public static UUID insertWithHeaders(
      JdbcTemplate jdbc, String aggregateId, String eventType, String headersJson) {
    return jdbc.queryForObject(
        """
        INSERT INTO outbox.outbox_event
          (event_id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers)
        VALUES (?, 'Procurement', ?, ?, 1, '{"k":1}'::jsonb, ?::jsonb)
        RETURNING event_id
        """,
        UUID.class,
        UUID.randomUUID(),
        aggregateId,
        eventType,
        headersJson);
  }

  public static int count(JdbcTemplate jdbc, String where) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox.outbox_event WHERE " + where, Integer.class);
  }
}
