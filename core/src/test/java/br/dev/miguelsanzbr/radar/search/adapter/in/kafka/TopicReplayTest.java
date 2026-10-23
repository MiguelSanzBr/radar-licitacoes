package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

class TopicReplayTest {

  private static final String TOPIC = "radar.procurement.v1";

  private final MockConsumer<String, String> consumer = new MockConsumer<>("earliest");
  private final ConsumerFactory<String, String> factory = mock();
  private final List<List<OffsetEvent>> batches = new ArrayList<>();
  private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_761_134_400_000L), ZoneOffset.UTC);

  TopicReplayTest() {
    when(factory.createConsumer(anyString(), anyString(), isNull(), any(Properties.class)))
        .thenReturn(consumer);
  }

  /** The topic has the given end offset per partition and starts at zero. */
  private void topic(long... ends) {
    var infos = new ArrayList<PartitionInfo>();
    var beginnings = new HashMap<TopicPartition, Long>();
    var endings = new HashMap<TopicPartition, Long>();
    for (var p = 0; p < ends.length; p++) {
      infos.add(new PartitionInfo(TOPIC, p, null, null, null));
      beginnings.put(new TopicPartition(TOPIC, p), 0L);
      endings.put(new TopicPartition(TOPIC, p), ends[p]);
    }
    consumer.updatePartitions(TOPIC, infos);
    consumer.updateBeginningOffsets(beginnings);
    consumer.updateEndOffsets(endings);
  }

  private void record(int partition, long offset, String value) {
    consumer.addRecord(new ConsumerRecord<>(TOPIC, partition, offset, "key", value));
  }

  private String discoveredJson(int n) {
    return envelope(discovered("d" + n, view(id(n), "object " + n, null, 1), 1));
  }

  private TopicReplay replay(Duration idle) {
    return new TopicReplay(factory, new EnvelopeParser(), TOPIC, clock, idle);
  }

  private long read(TopicReplay replay, int batchSize) {
    try (var snapshot = replay.snapshot()) {
      return snapshot.replay(batchSize, batches::add);
    }
  }

  @Test
  void readsEveryPartitionUpToItsEndOffsetInBatches() {
    topic(3, 2);
    consumer.schedulePollTask(
        () -> {
          record(0, 0, discoveredJson(1));
          record(0, 1, discoveredJson(2));
          record(0, 2, discoveredJson(3));
          record(1, 0, discoveredJson(4));
        });
    consumer.schedulePollTask(() -> record(1, 1, discoveredJson(5)));

    var count = read(replay(Duration.ofSeconds(5)), 2);

    assertThat(count).isEqualTo(5);
    assertThat(batches).extracting(List::size).containsExactly(2, 2, 1);
    assertThat(batches.stream().flatMap(List::stream).map(e -> e.event().aggregateId()))
        .containsExactlyInAnyOrder(id(1), id(2), id(3), id(4), id(5));
    assertThat(batches.stream().flatMap(List::stream).filter(e -> e.offset() == 2))
        .extracting(e -> e.event().aggregateId())
        .containsExactly(id(3));
  }

  @Test
  void recordsPastTheEndOffsetsCapturedAtTheStartBelongToTheLiveListener() {
    topic(2);
    consumer.schedulePollTask(
        () -> {
          record(0, 0, discoveredJson(1));
          record(0, 1, discoveredJson(2));
          record(0, 2, discoveredJson(3));
        });

    var count = read(replay(Duration.ofSeconds(5)), 10);

    assertThat(count).isEqualTo(2);
    assertThat(batches).hasSize(1);
    assertThat(batches.getFirst())
        .extracting(e -> e.event().aggregateId())
        .containsExactly(id(1), id(2));
  }

  @Test
  void poisonAndUnknownTypesAreSkippedButStillCountAsRead() {
    topic(4);
    consumer.schedulePollTask(
        () -> {
          record(0, 0, "{ not json");
          record(0, 1, discoveredJson(1));
          record(0, 2, "{}");
          record(
              0,
              3,
              envelope(
                  "Probe", closed("p", id(9), 1), JsonMapper.builder().build().createObjectNode()));
        });

    var count = read(replay(Duration.ofSeconds(5)), 10);

    assertThat(count).isEqualTo(4);
    assertThat(batches).hasSize(1);
    assertThat(batches.getFirst()).extracting(e -> e.event().aggregateId()).containsExactly(id(1));
  }

  @Test
  void anEmptyTopicReadsNothingAndNeverCallsTheSink() {
    topic(0, 0);

    var count = read(replay(Duration.ofSeconds(5)), 10);

    assertThat(count).isZero();
    assertThat(batches).isEmpty();
  }

  @Test
  void readsFromTheBeginningInAFreshGroupNamedAfterTheClock() {
    topic(1);
    consumer.schedulePollTask(() -> record(0, 0, discoveredJson(1)));

    read(replay(Duration.ofSeconds(5)), 10);

    verify(factory)
        .createConsumer(
            eq("search-replay-1761134400000"),
            eq("search-replay"),
            isNull(),
            argThat(
                (Properties p) ->
                    "earliest".equals(p.get("auto.offset.reset"))
                        && "false".equals(p.get("enable.auto.commit"))));
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void aTopicThatStopsAnsweringBeforeTheEndFailsInsteadOfWaitingForever() {
    topic(5);
    consumer.schedulePollTask(() -> record(0, 0, discoveredJson(1)));

    assertThatThrownBy(() -> read(replay(Duration.ofMillis(100)), 10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no records for")
        .hasMessageContaining("1 of 5");
    assertThat(consumer.closed()).isTrue();
  }

  @Test
  void aTopicWithoutPartitionsIsAnError() {
    consumer.updatePartitions(TOPIC, List.of());

    assertThatThrownBy(() -> replay(Duration.ofSeconds(1)).snapshot())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TOPIC);
    assertThat(consumer.closed()).isTrue();
  }
}
