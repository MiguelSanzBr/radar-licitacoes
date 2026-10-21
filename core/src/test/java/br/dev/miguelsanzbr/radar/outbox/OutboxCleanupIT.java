package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@OutboxSpringBootTest
class OutboxCleanupIT {

  @Autowired OutboxCleanup cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired OutboxProperties properties;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  private long insert(String publishedAgo, String createdAgo) {
    var id = OutboxRows.insert(jdbc, "A", "ProcurementUpdated");
    jdbc.update(
        "UPDATE outbox.outbox_event SET created_at = now() - ?::interval, published_at = "
            + (publishedAgo == null ? "NULL" : "now() - ?::interval")
            + " WHERE id = ?",
        publishedAgo == null
            ? new Object[] {createdAgo, id}
            : new Object[] {createdAgo, publishedAgo, id});
    return id;
  }

  @Test
  void removesOnlyPublishedRowsOlderThanTheRetention() {
    var oldPublished = insert("8 days", "9 days");
    var recentPublished = insert("1 day", "2 days");
    var veryOldUnpublished = insert(null, "400 days");
    var freshUnpublished = insert(null, "1 minute");

    var deleted = cleanup.cleanOnce();

    assertThat(deleted).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT id FROM outbox.outbox_event ORDER BY id", Long.class))
        .containsExactly(recentPublished, veryOldUnpublished, freshUnpublished)
        .doesNotContain(oldPublished);
  }

  @Test
  void neverDeletesUnpublishedRowsHoweverOld() {
    for (var i = 0; i < 5; i++) {
      insert(null, "1000 days");
    }

    assertThat(cleanup.cleanOnce()).isZero();
    assertThat(OutboxRows.count(jdbc, "published_at IS NULL")).isEqualTo(5);
  }

  @Test
  void loopsInBatchesUntilABatchComesBackShort() {
    var small = new OutboxCleanup(jdbc, new OutboxProperties(100, 10, properties.retention(), 3));
    for (var i = 0; i < 10; i++) {
      insert("8 days", "9 days");
    }

    assertThat(small.cleanOnce()).isEqualTo(10);
    assertThat(OutboxRows.count(jdbc, "true")).isZero();
  }

  @Test
  void stopsWhenTheLastBatchIsExactlyFull() {
    var small = new OutboxCleanup(jdbc, new OutboxProperties(100, 10, properties.retention(), 3));
    for (var i = 0; i < 6; i++) {
      insert("8 days", "9 days");
    }

    assertThat(small.cleanOnce()).isEqualTo(6);
  }
}
