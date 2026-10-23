package br.dev.miguelsanzbr.radar.ops.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;

class KafkaConsumerLagsTest {

  private static final TopicPartition P0 = new TopicPartition("radar.procurement.v1", 0);
  private static final TopicPartition P1 = new TopicPartition("radar.procurement.v1", 1);
  private static final TopicPartition ALERT = new TopicPartition("radar.alert.v1", 0);

  private final Admin admin = mock(Admin.class);

  private KafkaConsumerLags lags(Duration timeout, String... groups) {
    return new KafkaConsumerLags(() -> admin, List.of(groups), timeout);
  }

  private void committed(Map<String, Map<TopicPartition, OffsetAndMetadata>> groups) {
    var result = mock(ListConsumerGroupOffsetsResult.class);
    when(result.all()).thenReturn(KafkaFuture.completedFuture(groups));
    when(admin.listConsumerGroupOffsets(anyMap())).thenReturn(result);
  }

  private void latest(Map<TopicPartition, Long> ends) {
    var infos = new HashMap<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>();
    ends.forEach(
        (partition, end) ->
            infos.put(
                partition, new ListOffsetsResult.ListOffsetsResultInfo(end, 0L, Optional.empty())));
    var result = mock(ListOffsetsResult.class);
    when(result.all()).thenReturn(KafkaFuture.completedFuture(infos));
    when(admin.listOffsets(anyMap())).thenReturn(result);
  }

  @Test
  void theLagOfEveryGroupComesFromOneCallForTheCommitsAndOneForTheEnds() {
    committed(
        Map.of(
            "search-projector",
            Map.of(P0, new OffsetAndMetadata(8), P1, new OffsetAndMetadata(2)),
            "alerts-notifier",
            Map.of(ALERT, new OffsetAndMetadata(5)),
            "never-committed",
            Map.of()));
    latest(Map.of(P0, 10L, P1, 2L, ALERT, 5L));

    var result =
        lags(Duration.ofSeconds(5), "search-projector", "alerts-notifier", "never-committed")
            .read();

    assertThat(result)
        .containsExactlyInAnyOrder(
            new ConsumerLag("search-projector", "radar.procurement.v1", 2),
            new ConsumerLag("alerts-notifier", "radar.alert.v1", 0));
    verify(admin, times(1)).listConsumerGroupOffsets(anyMap());
    verify(admin, times(1)).listOffsets(anyMap());
  }

  @Test
  void aPartitionWithoutACommitIsLeftOut() {
    var commits = new HashMap<TopicPartition, OffsetAndMetadata>();
    commits.put(P0, new OffsetAndMetadata(1));
    commits.put(P1, null);
    committed(Map.of("g", commits));
    latest(Map.of(P0, 4L));

    assertThat(lags(Duration.ofSeconds(5), "g").read())
        .containsExactly(new ConsumerLag("g", "radar.procurement.v1", 3));
  }

  @Test
  void aCommitAheadOfTheEndNeverGivesANegativeLag() {
    committed(Map.of("g", Map.of(P0, new OffsetAndMetadata(9))));
    latest(Map.of(P0, 4L));

    assertThat(lags(Duration.ofSeconds(5), "g").read())
        .containsExactly(new ConsumerLag("g", "radar.procurement.v1", 0));
  }

  @Test
  void withNoCommitsAtAllTheEndsAreNotAsked() {
    committed(Map.of("g", Map.of()));

    assertThat(lags(Duration.ofSeconds(5), "g").read()).isEmpty();
    verify(admin, never()).listOffsets(anyMap());
  }

  @Test
  void aBrokerThatNeverAnswersEndsAsAnErrorAtTheDeadlineNotAHang() {
    var result = mock(ListConsumerGroupOffsetsResult.class);
    when(result.all()).thenReturn(new KafkaFutureImpl<>());
    when(admin.listConsumerGroupOffsets(anyMap())).thenReturn(result);
    var started = System.nanoTime();

    assertThatThrownBy(() -> lags(Duration.ofMillis(200), "g").read())
        .isInstanceOf(IllegalStateException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    verify(admin).close(any(Duration.class));
  }

  @Test
  void theDeadlineCoversBothCallsTogether() {
    committed(Map.of("g", Map.of(P0, new OffsetAndMetadata(1))));
    var ends = mock(ListOffsetsResult.class);
    when(ends.all()).thenReturn(new KafkaFutureImpl<>());
    when(admin.listOffsets(anyMap())).thenReturn(ends);

    assertThatThrownBy(() -> lags(Duration.ofMillis(200), "g").read())
        .isInstanceOf(IllegalStateException.class);
  }
}
