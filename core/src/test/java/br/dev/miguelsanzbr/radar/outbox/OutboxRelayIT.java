package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@OutboxSpringBootTest
class OutboxRelayIT {

  @Autowired OutboxRelay relay;
  @Autowired InMemoryEventPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired MeterRegistry registry;
  @Autowired OutboxProperties properties;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
    publisher.reset();
  }

  private long insert(String aggregateId) {
    return OutboxRows.insert(jdbc, aggregateId, "ProcurementUpdated");
  }

  @Test
  void defaultsMatchTheSpecification() {
    assertThat(properties.batchSize()).isEqualTo(100);
    assertThat(properties.maxAttempts()).isEqualTo(10);
    assertThat(properties.retention()).hasDays(7);
    assertThat(properties.cleanupBatchSize()).isEqualTo(10_000);
  }

  @Test
  void publishesInIdOrderAndMarksThemPublished() {
    var ids = List.of(insert("A"), insert("B"), insert("A"), insert("C"));

    var count = relay.relayOnce();

    assertThat(count).isEqualTo(4);
    assertThat(publisher.publishedIds()).containsExactlyElementsOf(ids);
    assertThat(OutboxRows.count(jdbc, "published_at IS NOT NULL")).isEqualTo(4);
    assertThat(relay.relayOnce()).isZero();
  }

  @Test
  void messageCarriesTheStoredColumns() {
    insert("A");

    relay.relayOnce();

    var message = publisher.published().getFirst();
    assertThat(message.aggregateType()).isEqualTo("Procurement");
    assertThat(message.aggregateId()).isEqualTo("A");
    assertThat(message.eventType()).isEqualTo("ProcurementUpdated");
    assertThat(message.schemaVersion()).isEqualTo(1);
    assertThat(message.payload()).contains("\"k\"");
    assertThat(message.headers()).containsEntry("eventType", "x").containsEntry("schemaVersion", 1);
    assertThat(message.createdAt()).isNotNull();
    assertThat(message.attempts()).isZero();
  }

  @Test
  void respectsTheBatchSize() {
    for (var i = 0; i < properties.batchSize() + 5; i++) {
      insert("A" + i);
    }

    assertThat(relay.relayOnce()).isEqualTo(properties.batchSize());
    assertThat(relay.relayOnce()).isEqualTo(5);
  }

  @Test
  void failureBlocksLaterEventsOfThatAggregateOnlyAndRecordsTheError() {
    var a1 = insert("A");
    var b1 = insert("B");
    var a2 = insert("A");
    var b2 = insert("B");
    publisher.failFor("A", 1);

    var count = relay.relayOnce();

    assertThat(count).isEqualTo(2);
    assertThat(publisher.publishedIds()).containsExactly(b1, b2);
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox.outbox_event WHERE id = ?", Integer.class, a1))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error FROM outbox.outbox_event WHERE id = ?", String.class, a1))
        .contains("broker unavailable");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox.outbox_event WHERE id = ?", Integer.class, a2))
        .isZero();
    assertThat(OutboxRows.count(jdbc, "id IN (" + a1 + "," + a2 + ") AND published_at IS NULL"))
        .isEqualTo(2);

    assertThat(relay.relayOnce()).isEqualTo(2);
    assertThat(publisher.publishedIds()).containsExactly(b1, b2, a1, a2);
  }

  @Test
  void lastErrorIsTruncatedTo2000Characters() {
    insert("A");
    EventPublisher huge =
        message -> {
          throw new PublishException("x".repeat(5000));
        };
    var relayWithHugeError = new OutboxRelay(jdbc, transactionManager, huge, properties, registry);

    relayWithHugeError.relayOnce();

    assertThat(
            jdbc.queryForObject(
                "SELECT length(last_error) FROM outbox.outbox_event", Integer.class))
        .isEqualTo(2000);
  }

  @Test
  void poisonAfterMaxAttemptsCountsOnceAndStopsBlocking() {
    var poison = insert("P");
    var other = insert("Q");
    publisher.failFor("P", Integer.MAX_VALUE);
    var before = registry.counter("outbox_poison_total").count();

    for (var i = 1; i < properties.maxAttempts(); i++) {
      relay.relayOnce();
      assertThat(registry.counter("outbox_poison_total").count()).isEqualTo(before);
    }
    relay.relayOnce();

    assertThat(registry.counter("outbox_poison_total").count()).isEqualTo(before + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox.outbox_event WHERE id = ?", Integer.class, poison))
        .isEqualTo(properties.maxAttempts());
    assertThat(publisher.publishedIds()).containsExactly(other);

    var attemptsSoFar = publisher.attempts();
    assertThat(relay.relayOnce()).isZero();
    assertThat(publisher.attempts()).isEqualTo(attemptsSoFar);
    assertThat(registry.counter("outbox_poison_total").count()).isEqualTo(before + 1);
  }

  @Test
  void laterEventOfAPoisonAggregateIsNoLongerBlockedByIt() {
    insert("P");
    publisher.failFor("P", properties.maxAttempts());
    for (var i = 0; i < properties.maxAttempts(); i++) {
      relay.relayOnce();
    }
    var later = insert("P");

    assertThat(relay.relayOnce()).isEqualTo(1);
    assertThat(publisher.publishedIds()).containsExactly(later);
  }

  @Test
  void gaugesReportPendingCountAndOldestAge() {
    assertThat(registry.get("outbox_pending").gauge().value()).isZero();
    assertThat(registry.get("outbox_oldest_age_seconds").gauge().value()).isZero();
    assertThat(registry.get("outbox_poison_pending").gauge().value()).isZero();

    insert("A");
    var oldest = insert("B");
    jdbc.update(
        "UPDATE outbox.outbox_event SET created_at = now() - interval '100 seconds' WHERE id = ?",
        oldest);
    jdbc.update(
        "UPDATE outbox.outbox_event SET created_at = now() - interval '200 seconds' WHERE id = (SELECT min(id) FROM outbox.outbox_event)");

    assertThat(registry.get("outbox_pending").gauge().value()).isEqualTo(2);
    assertThat(registry.get("outbox_oldest_age_seconds").gauge().value()).isBetween(199.0, 260.0);
  }

  @Test
  void poisonRowsLeaveTheOldestAgeAndShowUpInTheirOwnGauge() {
    var poison = insert("P");
    insert("Q");
    jdbc.update(
        "UPDATE outbox.outbox_event SET attempts = ?, created_at = now() - interval '1 day' WHERE id = ?",
        properties.maxAttempts(),
        poison);
    jdbc.update(
        "UPDATE outbox.outbox_event SET created_at = now() - interval '30 seconds' WHERE id <> ?",
        poison);

    assertThat(registry.get("outbox_poison_pending").gauge().value()).isEqualTo(1);
    assertThat(registry.get("outbox_oldest_age_seconds").gauge().value()).isBetween(29.0, 90.0);
  }

  @Test
  void runtimeExceptionFromThePublisherCountsAsAnAttemptAndIsolatesTheAggregate() {
    var a1 = insert("A");
    var b1 = insert("B");
    var a2 = insert("A");
    var seen = new ArrayList<Long>();
    EventPublisher flaky =
        message -> {
          if (message.aggregateId().equals("A")) {
            throw new IllegalStateException("serializer blew up");
          }
          seen.add(message.id());
        };
    var flakyRelay = new OutboxRelay(jdbc, transactionManager, flaky, properties, registry);

    var count = flakyRelay.relayOnce();

    assertThat(count).isEqualTo(1);
    assertThat(seen).containsExactly(b1);
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox.outbox_event WHERE id = ?", Integer.class, a1))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error FROM outbox.outbox_event WHERE id = ?", String.class, a1))
        .isEqualTo("java.lang.IllegalStateException: serializer blew up");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox.outbox_event WHERE id = ?", Integer.class, a2))
        .isZero();
  }

  @Test
  void poisonCounterIsNotIncrementedWhenTheBatchTransactionRollsBack() {
    var poison = insert("P");
    insert("Q");
    jdbc.update(
        "UPDATE outbox.outbox_event SET attempts = ? WHERE id = ?",
        properties.maxAttempts() - 1,
        poison);
    var before = registry.counter("outbox_poison_total").count();
    EventPublisher dies =
        message -> {
          if (message.aggregateId().equals("P")) {
            throw new PublishException("down");
          }
          throw new AssertionError("fatal after the poison failure was recorded");
        };
    var dyingRelay = new OutboxRelay(jdbc, transactionManager, dies, properties, registry);

    assertThatThrownBy(dyingRelay::relayOnce).isInstanceOf(AssertionError.class);

    assertThat(registry.counter("outbox_poison_total").count()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox.outbox_event WHERE id = ?", Integer.class, poison))
        .isEqualTo(properties.maxAttempts() - 1);
  }
}
