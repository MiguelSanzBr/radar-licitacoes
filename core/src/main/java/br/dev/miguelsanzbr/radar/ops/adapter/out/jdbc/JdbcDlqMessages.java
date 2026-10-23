package br.dev.miguelsanzbr.radar.ops.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.domain.DeadMessage;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcDlqMessages implements DlqMessages {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  JdbcDlqMessages(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void record(DeadMessage message) {
    jdbc.sql(
            """
            INSERT INTO ops.dlq_message (topic, kafka_partition, kafka_offset, message_key,
                                         message_value, headers, error_class, error_message,
                                         original_topic, original_partition, original_offset,
                                         consumer_group, recorded_at)
            VALUES (:topic, :partition, :offset, :key, :value, CAST(:headers AS jsonb),
                    :errorClass, :errorMessage, :originalTopic, :originalPartition,
                    :originalOffset, :consumerGroup, :recordedAt)
            ON CONFLICT (original_topic, original_partition, original_offset, consumer_group)
            DO NOTHING
            """)
        .param("topic", message.topic())
        .param("partition", message.partition())
        .param("offset", message.offset())
        .param("key", message.key())
        .param("value", message.value())
        .param("headers", json.writeValueAsString(message.headers()))
        .param("errorClass", message.errorClass())
        .param("errorMessage", message.errorMessage())
        .param("originalTopic", message.originalTopic())
        .param("originalPartition", message.originalPartition())
        .param("originalOffset", message.originalOffset())
        .param("consumerGroup", message.consumerGroup())
        .param("recordedAt", utc(message.recordedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
        .update();
  }

  @Override
  public List<Pending> pending(String topic, Long id, int limit) {
    return jdbc.sql(
            """
            SELECT id, message_key, message_value FROM ops.dlq_message
            WHERE topic = :topic AND reprocessed_at IS NULL AND (CAST(:id AS bigint) IS NULL OR id = :id)
            ORDER BY (reprocess_result IS NOT NULL), id
            LIMIT :limit
            """)
        .param("topic", topic)
        .param("limit", limit)
        .param("id", id, Types.BIGINT)
        .query((rs, row) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  @Override
  public void markReprocessed(long id, Instant at) {
    jdbc.sql(
            "UPDATE ops.dlq_message SET reprocessed_at = :at, reprocess_result = 'REPUBLISHED'"
                + " WHERE id = :id")
        .param("at", utc(at), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("id", id)
        .update();
  }

  @Override
  public void markFailed(long id, String result) {
    jdbc.sql("UPDATE ops.dlq_message SET reprocess_result = :result WHERE id = :id")
        .param("result", result)
        .param("id", id)
        .update();
  }

  @Override
  public List<DlqCount> unreprocessed() {
    return jdbc.sql(
            "SELECT topic, consumer_group, count(*) FROM ops.dlq_message WHERE reprocessed_at IS"
                + " NULL GROUP BY topic, consumer_group ORDER BY topic, consumer_group")
        .query((rs, row) -> new DlqCount(rs.getString(1), rs.getString(2), rs.getLong(3)))
        .list();
  }

  private static OffsetDateTime utc(Instant at) {
    return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }
}
