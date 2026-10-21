package br.dev.miguelsanzbr.radar.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Deletes published rows past the retention, in batches; unpublished rows are never touched. */
@Component
public class OutboxCleanup {

  private static final String DELETE =
      """
      DELETE FROM outbox.outbox_event WHERE id IN (
        SELECT id FROM outbox.outbox_event
        WHERE published_at IS NOT NULL AND published_at < now() - make_interval(secs => ?)
        LIMIT ?)
      """;

  private final JdbcTemplate jdbc;
  private final OutboxProperties properties;

  OutboxCleanup(JdbcTemplate jdbc, OutboxProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  /** Returns the total number of rows deleted. */
  public int cleanOnce() {
    var retentionSeconds = properties.retention().toSeconds();
    var total = 0;
    int deleted;
    do {
      deleted = jdbc.update(DELETE, retentionSeconds, properties.cleanupBatchSize());
      total += deleted;
    } while (deleted >= properties.cleanupBatchSize());
    return total;
  }
}
