package br.dev.miguelsanzbr.radar.search.application;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ReplayUseCaseTest {

  private static final Instant START = Instant.parse("2026-10-22T12:00:00Z");

  private final List<String> calls = new ArrayList<>();
  private final List<Integer> batchSizes = new ArrayList<>();
  private final List<OffsetEvent> events =
      List.of(new OffsetEvent(closed("a", id(1), 1), 0), new OffsetEvent(closed("b", id(2), 1), 1));
  private RuntimeException readFailure;
  private boolean liveWasRunning = true;
  private RuntimeException stopFailure;

  private final LiveProjection live =
      new LiveProjection() {
        @Override
        public boolean isRunning() {
          return liveWasRunning;
        }

        @Override
        public void stop() {
          calls.add("stop");
          if (stopFailure != null) {
            throw stopFailure;
          }
        }

        @Override
        public void start() {
          calls.add("start");
        }
      };

  private final ProjectionStore store =
      new FakeProjectionStore(ProjectionOutcome.APPLIED) {
        @Override
        public List<ProjectionOutcome> projectAll(List<OffsetEvent> batch) {
          calls.add("project " + batch.size());
          return super.projectAll(batch);
        }

        @Override
        public void reset() {
          calls.add("reset");
        }
      };

  private final EventLog log =
      () -> {
        calls.add("snapshot");
        return new EventLog.Snapshot() {
          @Override
          public long replay(int batchSize, Consumer<List<OffsetEvent>> sink) {
            batchSizes.add(batchSize);
            calls.add("read");
            if (readFailure != null) {
              throw readFailure;
            }
            sink.accept(events);
            return 7;
          }

          @Override
          public void close() {
            calls.add("close");
          }
        };
      };

  /** Reads 12:00:00 when the replay starts and 12:00:04 when it ends. */
  private final Clock clock =
      new Clock() {
        private final LinkedList<Instant> instants =
            new LinkedList<>(List.of(START, START.plusSeconds(4)));

        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return instants.removeFirst();
        }
      };

  private boolean lockTaken;

  /** Granted unless another instance holds it; records when it is released. */
  private final ReplayLock lock =
      () -> {
        if (lockTaken) {
          return Optional.empty();
        }
        lockTaken = true;
        calls.add("lock");
        return Optional.of(
            () -> {
              lockTaken = false;
              calls.add("unlock");
            });
      };

  private ReplayUseCase useCase() {
    return new ReplayUseCase(
        new ProcurementProjector(store, new NoMetrics(), Clock.systemUTC()),
        live,
        log,
        clock,
        lock);
  }

  private ReplayReport replay() {
    return useCase().claim().orElseThrow().run();
  }

  @Test
  void stopsTheLiveListenerReadsBeforeWipingThenProjectsAndRestarts() {
    replay();

    assertThat(calls)
        .containsExactly(
            "lock", "stop", "snapshot", "reset", "read", "project 2", "close", "start", "unlock");
  }

  @Test
  void aListenerThatWasNotRunningIsLeftStopped() {
    liveWasRunning = false;

    replay();

    assertThat(calls)
        .containsExactly("lock", "snapshot", "reset", "read", "project 2", "close", "unlock");
  }

  @Test
  void projectsInBatchesOf500() {
    replay();

    assertThat(batchSizes).containsExactly(500);
  }

  @Test
  void reportsTheRecordsReadAndTheElapsedTime() {
    var report = replay();

    assertThat(report.events()).isEqualTo(7);
    assertThat(report.startedAt()).isEqualTo(START);
    assertThat(report.finishedAt()).isEqualTo(START.plusSeconds(4));
    assertThat(report.duration().toSeconds()).isEqualTo(4);
    assertThat(report.eventsPerSecond()).isEqualTo(1.75);
  }

  @Test
  void aFailureStillClosesTheSnapshotAndRestartsTheLiveListener() {
    readFailure = new IllegalStateException("broker gone");

    assertThatThrownBy(ReplayUseCaseTest.this::replay)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("broker gone");

    assertThat(calls)
        .containsExactly("lock", "stop", "snapshot", "reset", "read", "close", "start", "unlock");
  }

  @Test
  void aListenerThatFailsToStopIsStillRestartedAndNothingIsWiped() {
    stopFailure = new IllegalStateException("stuck");

    assertThatThrownBy(ReplayUseCaseTest.this::replay).hasMessage("stuck");

    assertThat(calls).containsExactly("lock", "stop", "start", "unlock");
  }

  @Test
  void aSnapshotThatCannotBeTakenLeavesTheViewsUntouched() {
    var failing =
        new ReplayUseCase(
            new ProcurementProjector(store, new NoMetrics(), Clock.systemUTC()),
            live,
            () -> {
              calls.add("snapshot");
              throw new IllegalStateException("no broker");
            },
            clock,
            lock);

    assertThatThrownBy(() -> failing.claim().orElseThrow().run()).hasMessage("no broker");

    assertThat(calls).containsExactly("lock", "stop", "snapshot", "start", "unlock");
  }

  @Test
  void whileAnotherInstanceHoldsTheLockThereIsNothingToRun() {
    lockTaken = true;

    assertThat(useCase().claim()).isEmpty();

    assertThat(calls).isEmpty();
  }

  @Test
  void theLockIsTakenOnClaimAndHeldUntilTheRunEnds() {
    var claim = useCase().claim().orElseThrow();

    assertThat(calls).containsExactly("lock");
    assertThat(useCase().claim()).isEmpty();

    claim.run();

    assertThat(calls).endsWith("start", "unlock");
    assertThat(useCase().claim()).isPresent();
  }

  private static final class NoMetrics implements ProjectionMetrics {
    @Override
    public void lag(java.time.Duration lag) {}

    @Override
    public void deferred() {}
  }
}
