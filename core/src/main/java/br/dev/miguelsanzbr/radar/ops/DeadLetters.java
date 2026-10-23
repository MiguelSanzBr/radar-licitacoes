package br.dev.miguelsanzbr.radar.ops;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Clock;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * What every listener does with a record it gave up on: it goes to {@code <topic>.dlq} with its key
 * and value and plain text headers that say what failed, where it came from and when, and {@code
 * dlq_messages_total{topic}} counts it. The recorder and the reprocess of the ops context read
 * those topics back, so the headers are the contract of {@code ops.dlq_message}.
 */
public final class DeadLetters implements ConsumerAwareRecordRecoverer {

  public static final String ERROR_CLASS = "x-error-class";
  public static final String ERROR_MESSAGE = "x-error-message";
  public static final String ORIGINAL_TOPIC = "x-original-topic";
  public static final String ORIGINAL_PARTITION = "x-original-partition";
  public static final String ORIGINAL_OFFSET = "x-original-offset";
  public static final String FAILED_AT = "x-failed-at";
  public static final String CONSUMER_GROUP = "x-consumer-group";

  /** Marks a record the operator put back on its topic from the dead letters. */
  public static final String REPROCESSED_FROM = "x-reprocessed-from";

  private static final int MAX_MESSAGE = 1_024;

  private final DeadLetterPublishingRecoverer publisher;
  private final MeterRegistry registry;
  // the headers function runs on the thread of accept(), which is where the consumer is known
  private final ThreadLocal<String> group = new ThreadLocal<>();

  public DeadLetters(KafkaOperations<?, ?> template, MeterRegistry registry, Clock clock) {
    this.registry = registry;
    // A negative partition lets the producer pick it from the key, so a key stays in order.
    this.publisher =
        new DeadLetterPublishingRecoverer(
            template, (record, failure) -> new TopicPartition(dlqTopic(record.topic()), -1));
    publisher.excludeHeader(HeadersToAdd.values());
    publisher.addHeadersFunction(
        (record, failure) -> describe(record, failure, clock, group.get()));
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception failure) {
    group.set(consumer == null ? null : consumer.groupMetadata().groupId());
    try {
      publisher.accept(record, consumer, failure);
    } finally {
      group.remove();
    }
    registry.counter("dlq_messages_total", "topic", dlqTopic(record.topic())).increment();
  }

  /**
   * A failure of the database itself (connection, timeout, lock, no transaction): the record is
   * fine and must wait, never go to the dead letters. A constraint or size violation is bad data
   * and is not an outage. The jOOQ side has no Spring translator, so its raw {@link SQLException}
   * is judged by type and by SQLState class: 08 (connection), 40 (rollback, deadlock), 53
   * (resources), 57 (operator intervention, timeout) and 55P03 (lock not available).
   */
  public static boolean isOutage(Throwable failure) {
    for (var t = failure; t != null; t = t.getCause()) {
      if (t instanceof TransientDataAccessException
          || t instanceof DataAccessResourceFailureException
          || t instanceof CannotCreateTransactionException
          || t instanceof TransactionTimedOutException
          || (t instanceof SQLException sql && outage(sql))) {
        return true;
      }
    }
    return false;
  }

  /** True for a record the operator reprocessed from a dead-letter topic. */
  public static boolean reprocessed(ConsumerRecord<?, ?> record) {
    var marker = record.headers().lastHeader(REPROCESSED_FROM);
    return marker != null && "dlq".equals(new String(marker.value(), StandardCharsets.UTF_8));
  }

  private static boolean outage(SQLException sql) {
    var state = sql.getSQLState();
    return sql instanceof SQLTransientException
        || sql instanceof SQLRecoverableException
        || sql instanceof SQLNonTransientConnectionException
        || (state != null
            && (state.startsWith("08")
                || state.startsWith("40")
                || state.startsWith("53")
                || state.startsWith("57")
                || state.equals("55P03")));
  }

  /** The dead-letter topic of a topic. */
  public static String dlqTopic(String topic) {
    return topic + ".dlq";
  }

  private static Headers describe(
      ConsumerRecord<?, ?> record, Exception failure, Clock clock, String group) {
    var cause =
        failure instanceof ListenerExecutionFailedException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    var message = cause.getMessage() == null ? "" : cause.getMessage();
    var headers = new RecordHeaders();
    put(headers, ERROR_CLASS, cause.getClass().getName());
    put(headers, ERROR_MESSAGE, cut(message));
    put(headers, ORIGINAL_TOPIC, record.topic());
    put(headers, ORIGINAL_PARTITION, Integer.toString(record.partition()));
    put(headers, ORIGINAL_OFFSET, Long.toString(record.offset()));
    put(headers, FAILED_AT, clock.instant().toString());
    if (group != null) {
      put(headers, CONSUMER_GROUP, group);
    }
    return headers;
  }

  /** At most {@code MAX_MESSAGE} bytes of UTF-8, never in the middle of a character. */
  private static String cut(String message) {
    var bytes = message.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_MESSAGE) {
      return message;
    }
    var end = MAX_MESSAGE;
    while ((bytes[end] & 0xC0) == 0x80) {
      end--;
    }
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }

  private static void put(Headers headers, String name, String value) {
    headers.add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
