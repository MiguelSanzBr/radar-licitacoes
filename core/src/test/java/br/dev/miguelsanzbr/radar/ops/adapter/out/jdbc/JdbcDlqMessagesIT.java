package br.dev.miguelsanzbr.radar.ops.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages.Pending;
import br.dev.miguelsanzbr.radar.ops.domain.DeadMessage;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcDlqMessagesIT extends PostgresIT {

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");
  private static final String PROCUREMENT = "radar.procurement.v1.dlq";
  private static final String ALERT = "radar.alert.v1.dlq";

  @Autowired DlqMessages messages;

  @BeforeEach
  void emptyDeadLetters() {
    jdbc.sql("TRUNCATE ops.dlq_message").update();
  }

  private static DeadMessage message(String topic, long offset) {
    return message(topic, offset, "search-projector", offset);
  }

  /** A dead letter of the record {@code originalOffset} of the source topic, by one group. */
  private static DeadMessage message(String topic, long offset, String group, long originalOffset) {
    return new DeadMessage(
        topic,
        2,
        offset,
        "key-" + offset,
        "{\"value\":" + offset + "}",
        Map.of("x-error-class", "java.lang.IllegalStateException", "x-original-offset", "7"),
        "java.lang.IllegalStateException",
        "odd",
        topic.replace(".dlq", ""),
        3,
        originalOffset,
        group,
        AT);
  }

  @Test
  void aRecordedMessageKeepsEveryField() {
    messages.record(message(PROCUREMENT, 5));

    var row =
        jdbc.sql(
                "SELECT topic, kafka_partition, kafka_offset, message_key, message_value,"
                    + " headers->>'x-original-offset' AS original, error_class, error_message,"
                    + " recorded_at, reprocessed_at, reprocess_result FROM ops.dlq_message")
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("topic", PROCUREMENT)
        .containsEntry("kafka_partition", 2)
        .containsEntry("kafka_offset", 5L)
        .containsEntry("message_key", "key-5")
        .containsEntry("message_value", "{\"value\":5}")
        .containsEntry("original", "7")
        .containsEntry("error_class", "java.lang.IllegalStateException")
        .containsEntry("error_message", "odd")
        .containsEntry("reprocessed_at", null)
        .containsEntry("reprocess_result", null);
    assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant()).isEqualTo(AT);
  }

  @Test
  void aMessageWithoutKeyOrDescriptionIsRecordedToo() {
    messages.record(
        new DeadMessage(
            ALERT, 0, 1, null, null, Map.of(), null, null, "radar.alert.v1", 0, 1, "", AT));

    assertThat(jdbc.sql("SELECT count(*) FROM ops.dlq_message").query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void theSameOriginalRecordFailedByTheSameGroupIsRecordedOnceWhateverItsDeadLetterOffset() {
    messages.record(message(PROCUREMENT, 5, "search-projector", 40));
    // the dead letter was published twice (lost ack): another offset, same original record
    messages.record(message(PROCUREMENT, 6, "search-projector", 40));
    // another group failed the same original record: its own row
    messages.record(message(PROCUREMENT, 7, "alerts-matcher", 40));
    messages.record(message(PROCUREMENT, 8, "search-projector", 41));

    assertThat(jdbc.sql("SELECT count(*) FROM ops.dlq_message").query(Integer.class).single())
        .isEqualTo(3);
  }

  @Test
  void theConsumerGroupAndTheOriginIsStored() {
    messages.record(message(PROCUREMENT, 5, "alerts-matcher", 40));

    var row =
        jdbc.sql(
                "SELECT consumer_group, original_topic, original_partition, original_offset"
                    + " FROM ops.dlq_message")
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("consumer_group", "alerts-matcher")
        .containsEntry("original_topic", "radar.procurement.v1")
        .containsEntry("original_partition", 3)
        .containsEntry("original_offset", 40L);
  }

  @Test
  void pendingListsTheUnreprocessedOfOneTopicOldestFirst() {
    messages.record(message(PROCUREMENT, 8));
    messages.record(message(PROCUREMENT, 3));
    messages.record(message(ALERT, 1));

    var pending = messages.pending(PROCUREMENT, null, 500);

    assertThat(pending).extracting(Pending::key).containsExactly("key-8", "key-3");
    assertThat(pending.getFirst().value()).isEqualTo("{\"value\":8}");
  }

  @Test
  void aRowThatFailedBeforeComesAfterTheFreshOnes() {
    messages.record(message(PROCUREMENT, 1));
    messages.record(message(PROCUREMENT, 2));
    messages.record(message(PROCUREMENT, 3));
    messages.markFailed(messages.pending(PROCUREMENT, null, 500).getFirst().id(), "FAILED: x");

    assertThat(messages.pending(PROCUREMENT, null, 500))
        .extracting(Pending::key)
        .containsExactly("key-2", "key-3", "key-1");
  }

  @Test
  void pendingStopsAtTheLimit() {
    messages.record(message(PROCUREMENT, 1));
    messages.record(message(PROCUREMENT, 2));
    messages.record(message(PROCUREMENT, 3));

    assertThat(messages.pending(PROCUREMENT, null, 2))
        .extracting(Pending::key)
        .containsExactly("key-1", "key-2");
  }

  @Test
  void pendingCanBeNarrowedToOneId() {
    messages.record(message(PROCUREMENT, 8));
    messages.record(message(PROCUREMENT, 3));
    var second = messages.pending(PROCUREMENT, null, 500).get(1);

    assertThat(messages.pending(PROCUREMENT, second.id(), 500)).containsExactly(second);
    assertThat(messages.pending(ALERT, second.id(), 500)).isEmpty();
  }

  @Test
  void aReprocessedMessageIsNoLongerPendingAndKeepsTheMoment() {
    messages.record(message(PROCUREMENT, 8));
    var id = messages.pending(PROCUREMENT, null, 500).getFirst().id();

    messages.markReprocessed(id, AT.plusSeconds(60));

    assertThat(messages.pending(PROCUREMENT, null, 500)).isEmpty();
    var row =
        jdbc.sql("SELECT reprocessed_at, reprocess_result FROM ops.dlq_message")
            .query()
            .singleRow();
    assertThat(((java.sql.Timestamp) row.get("reprocessed_at")).toInstant())
        .isEqualTo(AT.plusSeconds(60));
    assertThat(row).containsEntry("reprocess_result", "REPUBLISHED");
  }

  @Test
  void aFailedRepublishStaysPendingWithTheReason() {
    messages.record(message(PROCUREMENT, 8));
    var id = messages.pending(PROCUREMENT, null, 500).getFirst().id();

    messages.markFailed(id, "FAILED: broker down");

    assertThat(messages.pending(PROCUREMENT, null, 500)).hasSize(1);
    assertThat(
            jdbc.sql("SELECT reprocess_result FROM ops.dlq_message").query(String.class).single())
        .isEqualTo("FAILED: broker down");
  }

  @Test
  void unreprocessedCountsPerTopicIgnoringTheReprocessed() {
    messages.record(message(PROCUREMENT, 1, "search-projector", 1));
    messages.record(message(PROCUREMENT, 2, "search-projector", 2));
    messages.record(message(PROCUREMENT, 3, "alerts-matcher", 3));
    messages.record(message(ALERT, 1, "alerts-notifier", 1));
    messages.markReprocessed(messages.pending(ALERT, null, 500).getFirst().id(), AT);

    assertThat(messages.unreprocessed())
        .containsExactly(
            new DlqCount(PROCUREMENT, "alerts-matcher", 1),
            new DlqCount(PROCUREMENT, "search-projector", 2));
  }
}
