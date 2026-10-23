package br.dev.miguelsanzbr.radar.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes rows past the retention, in batches. Polling mode only deletes published rows, so an
 * unpublished row is never touched. In CDC mode nobody sets {@code published_at}, so rows go by
 * {@code created_at}. That cannot lose an event Debezium has not read: the replication slot holds
 * the WAL of the insert until the connector confirms it, whatever the table does meanwhile. The
 * real risk is the slot being invalidated by {@code max_slot_wal_keep_size}, which the slot alerts
 * watch (docs/runbook.md#slot-do-cdc-atrasado).
 */
@Component
public class OutboxCleanup {

  private static final String DELETE =
      """
      DELETE FROM outbox.outbox_event WHERE id IN (
        SELECT id FROM outbox.outbox_event
        WHERE {age} < now() - make_interval(secs => ?) LIMIT ?)
      """;

  private final JdbcTemplate jdbc;
  private final OutboxProperties properties;
  private final String delete;

  OutboxCleanup(JdbcTemplate jdbc, OutboxProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
    // why: a NULL published_at never satisfies the comparison, so polling keeps unpublished rows
    this.delete =
        DELETE.replace(
            "{age}",
            properties.mode() == OutboxProperties.Mode.CDC ? "created_at" : "published_at");
  }

  /** Returns the total number of rows deleted. */
  public int cleanOnce() {
    var retentionSeconds = properties.retention().toSeconds();
    var total = 0;
    int deleted;
    do {
      deleted = jdbc.update(delete, retentionSeconds, properties.cleanupBatchSize());
      total += deleted;
    } while (deleted >= properties.cleanupBatchSize());
    return total;
  }
}
