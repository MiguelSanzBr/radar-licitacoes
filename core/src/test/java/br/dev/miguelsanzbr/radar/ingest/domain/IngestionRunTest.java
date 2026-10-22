package br.dev.miguelsanzbr.radar.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IngestionRunTest {

  private static final Instant T0 = Instant.parse("2026-10-22T03:00:00Z");
  private static final Instant START = Instant.parse("2026-10-20T03:00:00Z");
  private static final Instant END = Instant.parse("2026-10-21T03:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
  private static final Clock LATER = Clock.fixed(T0.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);

  private static IngestionRun started() {
    return IngestionRun.start(IngestionKind.BACKFILL, 6, START, END, CLOCK);
  }

  @Test
  void startsRunningWithoutIdCursorOrCounters() {
    var run = started();

    assertThat(run.id()).isNull();
    assertThat(run.version()).isZero();
    assertThat(run.kind()).isEqualTo(IngestionKind.BACKFILL);
    assertThat(run.modalityId()).isEqualTo(6);
    assertThat(run.windowStart()).isEqualTo(START);
    assertThat(run.windowEnd()).isEqualTo(END);
    assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.cursorPage()).isZero();
    assertThat(run.nextPage()).isEqualTo(1);
    assertThat(run.fetched()).isZero();
    assertThat(run.discovered()).isZero();
    assertThat(run.updated()).isZero();
    assertThat(run.unchanged()).isZero();
    assertThat(run.startedAt()).isEqualTo(T0);
    assertThat(run.finishedAt()).isNull();
    assertThat(run.error()).isNull();
  }

  @Test
  void pagesAdvanceTheCursorAndAccumulateCounters() {
    var run = started();

    run.pageProcessed(1, 50, 30, 10, 10);
    run.pageProcessed(2, 20, 1, 2, 17);

    assertThat(run.cursorPage()).isEqualTo(2);
    assertThat(run.nextPage()).isEqualTo(3);
    assertThat(run.fetched()).isEqualTo(70);
    assertThat(run.discovered()).isEqualTo(31);
    assertThat(run.updated()).isEqualTo(12);
    assertThat(run.unchanged()).isEqualTo(27);
  }

  @Test
  void aPageOtherThanTheNextOneIsRejectedAndChangesNothing() {
    var run = started();
    run.pageProcessed(1, 50, 50, 0, 0);

    assertThatThrownBy(() -> run.pageProcessed(3, 1, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3")
        .hasMessageContaining("2");
    assertThatThrownBy(() -> run.pageProcessed(1, 1, 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(run.cursorPage()).isEqualTo(1);
    assertThat(run.fetched()).isEqualTo(50);
  }

  @Test
  void finishMarksDoneAndStampsTheTime() {
    var run = started();

    run.finish(LATER);

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.finishedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
  }

  @Test
  void failKeepsTheCursorAndRecordsTheError() {
    var run = started();
    run.pageProcessed(1, 50, 50, 0, 0);

    run.fail("boom", LATER);

    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.error()).isEqualTo("boom");
    assertThat(run.cursorPage()).isEqualTo(1);
    assertThat(run.finishedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
  }

  @Test
  void longErrorsAreCutAtTwoThousandCharacters() {
    var run = started();

    run.fail("x".repeat(2500), CLOCK);

    assertThat(run.error()).hasSize(2000);
  }

  @Test
  void anErrorOfExactlyTwoThousandCharactersIsKept() {
    var run = started();

    run.fail("y".repeat(2000), CLOCK);

    assertThat(run.error()).isEqualTo("y".repeat(2000));
  }

  @Test
  void resumeAfterFailureRunsAgainKeepingCursorAndCounters() {
    var run = started();
    run.pageProcessed(1, 50, 40, 5, 5);
    run.pageProcessed(2, 50, 50, 0, 0);
    run.fail("boom", CLOCK);

    run.resume(LATER);

    assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.error()).isNull();
    assertThat(run.finishedAt()).isNull();
    assertThat(run.cursorPage()).isEqualTo(2);
    assertThat(run.nextPage()).isEqualTo(3);
    assertThat(run.fetched()).isEqualTo(100);
    assertThat(run.startedAt()).isEqualTo(T0);
  }

  @Test
  void resumeOfARunningRunIsANoOp() {
    var run = started();
    run.pageProcessed(1, 5, 5, 0, 0);

    run.resume(LATER);

    assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.cursorPage()).isEqualTo(1);
  }

  @Test
  void aDoneRunCannotBeResumed() {
    var run = started();
    run.finish(CLOCK);

    assertThatThrownBy(() -> run.resume(LATER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DONE");
  }

  @Test
  void restoreRebuildsEveryField() {
    var run =
        IngestionRun.restore(
            9L,
            3L,
            IngestionKind.REDISCOVERY,
            12,
            START,
            END,
            4,
            RunStatus.FAILED,
            200,
            100,
            60,
            40,
            T0,
            T0.plusSeconds(1),
            "e");

    assertThat(run.id()).isEqualTo(9L);
    assertThat(run.version()).isEqualTo(3L);
    assertThat(run.kind()).isEqualTo(IngestionKind.REDISCOVERY);
    assertThat(run.modalityId()).isEqualTo(12);
    assertThat(run.cursorPage()).isEqualTo(4);
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.fetched()).isEqualTo(200);
    assertThat(run.discovered()).isEqualTo(100);
    assertThat(run.updated()).isEqualTo(60);
    assertThat(run.unchanged()).isEqualTo(40);
    assertThat(run.startedAt()).isEqualTo(T0);
    assertThat(run.finishedAt()).isEqualTo(T0.plusSeconds(1));
    assertThat(run.error()).isEqualTo("e");
  }
}
