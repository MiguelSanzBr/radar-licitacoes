package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.search.application.EventLog;
import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * The procurement topic read by a plain consumer in a group of its own ({@code search-replay-<epoch
 * millis>}), assigned to every partition and positioned at the start. It reads until each partition
 * reaches the end offset captured when the snapshot was taken: what arrives later is left to the
 * live listener. It never commits.
 */
class TopicReplay implements EventLog {

  private static final Logger LOG = LoggerFactory.getLogger(TopicReplay.class);
  private static final Duration POLL = Duration.ofSeconds(1);

  private final ConsumerFactory<String, String> consumers;
  private final EnvelopeParser parser;
  private final String topic;
  private final Clock clock;
  private final Duration idleTimeout;

  TopicReplay(
      ConsumerFactory<String, String> consumers,
      EnvelopeParser parser,
      String topic,
      Clock clock,
      Duration idleTimeout) {
    this.consumers = consumers;
    this.parser = parser;
    this.topic = topic;
    this.clock = clock;
    this.idleTimeout = idleTimeout;
  }

  @Override
  public Snapshot snapshot() {
    var overrides = new Properties();
    overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    var consumer =
        consumers.createConsumer(
            "search-replay-" + clock.millis(), "search-replay", null, overrides);
    Snapshot reading = null;
    try {
      var partitions =
          consumer.partitionsFor(topic).stream()
              .map(p -> new TopicPartition(topic, p.partition()))
              .toList();
      if (partitions.isEmpty()) {
        throw new IllegalStateException("topic " + topic + " has no partitions");
      }
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      reading = new Reading(consumer, Map.copyOf(consumer.endOffsets(partitions)));
      return reading;
    } finally {
      if (reading == null) {
        consumer.close();
      }
    }
  }

  private final class Reading implements Snapshot {

    private final org.apache.kafka.clients.consumer.Consumer<String, String> consumer;
    private final Map<TopicPartition, Long> ends;

    Reading(
        org.apache.kafka.clients.consumer.Consumer<String, String> consumer,
        Map<TopicPartition, Long> ends) {
      this.consumer = consumer;
      this.ends = ends;
    }

    @Override
    public long replay(int batchSize, Consumer<List<OffsetEvent>> sink) {
      var batch = new ArrayList<OffsetEvent>(batchSize);
      long read = 0;
      var lastRecord = System.nanoTime();
      while (!atTheEnd()) {
        var records = consumer.poll(POLL);
        if (records.isEmpty()) {
          if (System.nanoTime() - lastRecord > idleTimeout.toNanos()) {
            throw new IllegalStateException(
                "no records for %s with %d of %d read"
                    .formatted(idleTimeout, read, ends.values().stream().mapToLong(o -> o).sum()));
          }
          continue;
        }
        lastRecord = System.nanoTime();
        for (var record : records) {
          if (record.offset() >= ends.get(new TopicPartition(record.topic(), record.partition()))) {
            continue;
          }
          read++;
          parse(record.value(), record.offset(), record.partition(), batch);
          if (batch.size() == batchSize) {
            flush(batch, sink);
          }
        }
      }
      flush(batch, sink);
      return read;
    }

    private boolean atTheEnd() {
      return ends.entrySet().stream()
          .allMatch(end -> consumer.position(end.getKey()) >= end.getValue());
    }

    @Override
    public void close() {
      consumer.close();
    }
  }

  /** Poison (anything the parser rejects) is skipped here as it is by the live listener. */
  private void parse(String json, long offset, int partition, List<OffsetEvent> batch) {
    try {
      parser.parse(json).ifPresent(parsed -> batch.add(new OffsetEvent(parsed.event(), offset)));
    } catch (RuntimeException e) {
      LOG.warn("replay skipped a record of {}-{}@{}", topic, partition, offset, e);
    }
  }

  private static void flush(List<OffsetEvent> batch, Consumer<List<OffsetEvent>> sink) {
    if (!batch.isEmpty()) {
      sink.accept(List.copyOf(batch));
      batch.clear();
    }
  }
}
