package br.dev.miguelsanzbr.radar.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ops.application.ReprocessDlqUseCase.Result;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages.Pending;
import br.dev.miguelsanzbr.radar.ops.application.port.Republisher;
import br.dev.miguelsanzbr.radar.ops.domain.DeadMessage;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReprocessDlqUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final String DLQ = "radar.procurement.v1.dlq";

  /** Keeps the pending messages and what was done with them. */
  private static final class FakeMessages implements DlqMessages {

    final List<Pending> pending = new ArrayList<>();
    final List<String> marks = new ArrayList<>();
    Long askedId = Long.MIN_VALUE;
    int askedLimit;
    boolean markFails;
    boolean markFailedFails;

    @Override
    public void record(DeadMessage message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Pending> pending(String topic, Long id, int limit) {
      askedId = id;
      askedLimit = limit;
      return topic.equals(DLQ)
          ? pending.stream().filter(p -> id == null || p.id() == id).limit(limit).toList()
          : List.of();
    }

    @Override
    public void markReprocessed(long id, Instant at) {
      if (markFails) {
        throw new IllegalStateException("db down");
      }
      marks.add("ok " + id + " " + at);
    }

    @Override
    public void markFailed(long id, String result) {
      if (markFailedFails) {
        throw new IllegalStateException("db down too");
      }
      marks.add("failed " + id + " " + result);
    }

    @Override
    public List<DlqCount> unreprocessed() {
      throw new UnsupportedOperationException();
    }
  }

  private final FakeMessages messages = new FakeMessages();
  private final List<String> sent = new ArrayList<>();
  private Republisher republisher =
      (topic, key, value) -> sent.add(topic + "|" + key + "|" + value);
  private final Set<String> topics = Set.of(DLQ, "radar.alert.v1.dlq");

  private ReprocessDlqUseCase useCase(int pageSize) {
    return new ReprocessDlqUseCase(
        messages, republisher, Clock.fixed(NOW, ZoneOffset.UTC), topics, pageSize);
  }

  private ReprocessDlqUseCase useCase() {
    return useCase(500);
  }

  @Test
  void everyPendingMessageGoesBackToTheOriginalTopicAndIsMarked() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    messages.pending.add(new Pending(2, null, "v2"));

    var result = useCase().reprocess(DLQ, null);

    assertThat(result).isEqualTo(new Result(2, false));
    assertThat(sent).containsExactly("radar.procurement.v1|k1|v1", "radar.procurement.v1|null|v2");
    assertThat(messages.marks).containsExactly("ok 1 " + NOW, "ok 2 " + NOW);
  }

  @Test
  void aRunHandlesOnePageAndSaysThatMoreRemain() {
    for (var i = 1; i <= 3; i++) {
      messages.pending.add(new Pending(i, "k" + i, "v" + i));
    }

    var result = useCase(2).reprocess(DLQ, null);

    assertThat(result).isEqualTo(new Result(2, true));
    assertThat(messages.askedLimit).isEqualTo(3);
    assertThat(sent).containsExactly("radar.procurement.v1|k1|v1", "radar.procurement.v1|k2|v2");
  }

  @Test
  void aPageThatFillsExactlyLeavesNothingMore() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    messages.pending.add(new Pending(2, "k2", "v2"));

    assertThat(useCase(2).reprocess(DLQ, null)).isEqualTo(new Result(2, false));
  }

  @Test
  void anIdNarrowsItToOneMessage() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    messages.pending.add(new Pending(2, "k2", "v2"));

    var result = useCase().reprocess(DLQ, 2L);

    assertThat(result.count()).isEqualTo(1);
    assertThat(messages.askedId).isEqualTo(2L);
    assertThat(sent).containsExactly("radar.procurement.v1|k2|v2");
  }

  @Test
  void nothingPendingIsAnEmptyRunNotAnError() {
    assertThat(useCase().reprocess(DLQ, null)).isEqualTo(new Result(0, false));
    assertThat(sent).isEmpty();
  }

  @Test
  void anIdWithNothingPendingIsNotFound() {
    messages.pending.add(new Pending(1, "k1", "v1"));

    assertThatThrownBy(() -> useCase().reprocess(DLQ, 7L))
        .isInstanceOf(DlqNotFoundException.class)
        .hasMessageContaining("7");
  }

  @Test
  void aTopicThatIsNotADeadLetterTopicIsNotFound() {
    assertThatThrownBy(() -> useCase().reprocess("radar.procurement.v1", null))
        .isInstanceOf(DlqNotFoundException.class)
        .hasMessageContaining("radar.procurement.v1");
    assertThat(sent).isEmpty();
  }

  @Test
  void theFirstFailedRepublishStopsTheRunWithTheCountDoneSoFar() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    messages.pending.add(new Pending(2, "k2", "v2"));
    messages.pending.add(new Pending(3, "k3", "v3"));
    republisher =
        (topic, key, value) -> {
          if (key.equals("k2")) {
            throw new IllegalStateException("broker down");
          }
          sent.add(key);
        };

    assertThatThrownBy(() -> useCase().reprocess(DLQ, null))
        .isInstanceOfSatisfying(
            ReprocessInterruptedException.class,
            e -> {
              assertThat(e.done()).isEqualTo(1);
              assertThat(e.getMessage()).contains("IllegalStateException: broker down");
            });

    assertThat(sent).containsExactly("k1");
    assertThat(messages.marks)
        .containsExactly("ok 1 " + NOW, "failed 2 FAILED: IllegalStateException: broker down");
  }

  @Test
  void aMarkThatFailsWhileReportingAFailedRepublishDoesNotMaskTheInterruption() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    messages.markFailedFails = true;
    republisher =
        (topic, key, value) -> {
          throw new IllegalStateException("broker down");
        };

    assertThatThrownBy(() -> useCase().reprocess(DLQ, null))
        .isInstanceOfSatisfying(
            ReprocessInterruptedException.class,
            e -> {
              assertThat(e.done()).isZero();
              assertThat(e.getMessage()).contains("broker down");
            });
  }

  @Test
  void aFailureWithoutMessageIsDescribedByItsClass() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    republisher =
        (topic, key, value) -> {
          throw new IllegalStateException();
        };

    assertThatThrownBy(() -> useCase().reprocess(DLQ, null))
        .isInstanceOf(ReprocessInterruptedException.class);

    assertThat(messages.marks).containsExactly("failed 1 FAILED: IllegalStateException");
  }

  @Test
  void aMarkThatFailsAfterASuccessfulSendIsNotAFailedRepublishAndStopsTheRun() {
    messages.pending.add(new Pending(1, "k1", "v1"));
    messages.pending.add(new Pending(2, "k2", "v2"));
    messages.markFails = true;

    var result = useCase().reprocess(DLQ, null);

    assertThat(result.count()).isEqualTo(1);
    assertThat(result.more()).isTrue();
    assertThat(result.markFailure()).isEqualTo("java.lang.IllegalStateException: db down");
    assertThat(sent).containsExactly("radar.procurement.v1|k1|v1");
    assertThat(messages.marks).isEmpty();
  }
}
