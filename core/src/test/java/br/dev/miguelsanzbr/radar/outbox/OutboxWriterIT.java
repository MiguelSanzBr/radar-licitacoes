package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@OutboxSpringBootTest
class OutboxWriterIT {

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");

  @Autowired OutboxWriter writer;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  @Test
  void aWriteInsideATransactionIsAPendingRowOfThatAggregateType() {
    transaction.executeWithoutResult(
        status -> writer.write("Alert", "s-1", 1, "AlertMatched", AT, Map.of("k", "v")));

    var row = jdbc.queryForMap("SELECT * FROM outbox.outbox_event");
    assertThat(row)
        .containsEntry("aggregate_type", "Alert")
        .containsEntry("aggregate_id", "s-1")
        .containsEntry("event_type", "AlertMatched");
    assertThat(row.get("published_at")).isNull();
  }

  @Test
  void aWriteOutsideATransactionIsRejected() {
    assertThatThrownBy(() -> writer.write("Alert", "s-1", 1, "AlertMatched", AT, Map.of()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }
}
