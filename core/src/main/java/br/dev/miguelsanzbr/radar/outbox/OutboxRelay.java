package br.dev.miguelsanzbr.radar.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes pending rows in id order. Delivery is at-least-once: a crash between the broker ack and
 * the commit republishes the batch. A failure of one message (a {@link PublishException} or any
 * runtime exception) is retried up to {@code maxAttempts} times; an {@link Error} aborts the batch.
 */
@Component
public class OutboxRelay {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> HEADERS = new TypeReference<>() {};

  private static final String SELECT =
      """
      SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
             payload::text, headers::text, created_at, attempts
      FROM outbox.outbox_event
      WHERE published_at IS NULL AND attempts < ?
      ORDER BY id
      LIMIT ?
      FOR UPDATE SKIP LOCKED
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final EventPublisher publisher;
  private final OutboxProperties properties;
  private final Counter poison;

  OutboxRelay(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      EventPublisher publisher,
      OutboxProperties properties,
      MeterRegistry registry) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
    this.publisher = publisher;
    this.properties = properties;
    this.poison = registry.counter("outbox_poison_total");
  }

  /** Publishes one batch in its own transaction and returns how many messages went out. */
  public int relayOnce() {
    return transaction.execute(status -> publishBatch());
  }

  private int publishBatch() {
    var batch =
        jdbc.query(
            SELECT, OutboxRelay::toMessage, properties.maxAttempts(), properties.batchSize());
    var blockedAggregates = new HashSet<String>();
    var publishedIds = new ArrayList<Long>();
    for (var message : batch) {
      if (blockedAggregates.contains(message.aggregateId())) {
        continue;
      }
      try {
        publisher.publish(message);
        publishedIds.add(message.id());
      } catch (PublishException | RuntimeException e) {
        if (e instanceof BrokerUnavailableException) {
          // why: the broker is down for everyone: trying every other aggregate only burns
          // timeouts, and an outage is not the message's fault, so it costs no attempt
          recordError(message, e);
          break;
        }
        recordFailure(message, e);
        // why: later events of the same aggregate must not overtake the failed one
        blockedAggregates.add(message.aggregateId());
      }
    }
    jdbc.update(
        "UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY (?)",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", publishedIds.toArray())));
    return publishedIds.size();
  }

  private void recordError(OutboxMessage message, Exception e) {
    jdbc.update(
        "UPDATE outbox.outbox_event SET last_error = left(?, 2000) WHERE id = ?",
        e.toString(),
        message.id());
  }

  private void recordFailure(OutboxMessage message, Exception e) {
    jdbc.update(
        "UPDATE outbox.outbox_event SET attempts = attempts + 1, last_error = left(?, 2000) WHERE id = ?",
        e.toString(),
        message.id());
    if (message.attempts() + 1 >= properties.maxAttempts()) {
      // why: counted only if the batch commits, so a rollback and retry cannot count it twice
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              poison.increment();
            }
          });
    }
  }

  private static OutboxMessage toMessage(ResultSet rs, int row) throws SQLException {
    return new OutboxMessage(
        rs.getLong("id"),
        rs.getObject("event_id", java.util.UUID.class),
        rs.getString("aggregate_type"),
        rs.getString("aggregate_id"),
        rs.getString("event_type"),
        rs.getInt("schema_version"),
        rs.getString("payload"),
        JSON.readValue(rs.getString("headers"), HEADERS),
        rs.getTimestamp("created_at").toInstant(),
        rs.getInt("attempts"));
  }
}
