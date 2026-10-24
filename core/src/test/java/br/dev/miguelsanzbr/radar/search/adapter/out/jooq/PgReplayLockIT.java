package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.EventLog;
import br.dev.miguelsanzbr.radar.search.application.LiveProjection;
import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import br.dev.miguelsanzbr.radar.search.application.ProjectionMetrics;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.application.ReplayLock;
import br.dev.miguelsanzbr.radar.search.application.ReplayUseCase;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Two instances of the use case over the real lock: only one replay runs cluster-wide. */
@OutboxSpringBootTest
class PgReplayLockIT {

  @Autowired ReplayLock lock;

  private static final LiveProjection STOPPED =
      new LiveProjection() {
        @Override
        public boolean isRunning() {
          return false;
        }

        @Override
        public void stop() {}

        @Override
        public void start() {}
      };

  private static final ProjectionMetrics NO_METRICS =
      new ProjectionMetrics() {
        @Override
        public void lag(Duration lag) {}

        @Override
        public void deferred() {}
      };

  private ReplayUseCase instance(EventLog log) {
    var store =
        new ProjectionStore() {
          @Override
          public ProjectionOutcome project(ProcurementEvent event, long offset) {
            throw new UnsupportedOperationException();
          }

          @Override
          public List<ProjectionOutcome> projectAll(List<OffsetEvent> batch) {
            return List.of();
          }

          @Override
          public void reset() {}
        };
    return new ReplayUseCase(
        new ProcurementProjector(store, NO_METRICS, Clock.systemUTC()),
        STOPPED,
        log,
        Clock.systemUTC(),
        lock);
  }

  private static EventLog log(Runnable duringRead) {
    return () ->
        new EventLog.Snapshot() {
          @Override
          public long replay(int batchSize, Consumer<List<OffsetEvent>> sink) {
            duringRead.run();
            return 0;
          }

          @Override
          public void close() {}
        };
  }

  @Test
  void aSecondInstanceCannotReplayWhileTheFirstDoesAndCanAfterwards() {
    var secondGotIt = new AtomicBoolean(true);
    var second = instance(log(() -> {}));
    var first = instance(log(() -> secondGotIt.set(second.claim().isPresent())));

    first.claim().orElseThrow().run();

    assertThat(secondGotIt).isFalse();
    assertThat(second.claim().map(ReplayUseCase.Claim::run)).isPresent();
  }

  @Test
  void aClaimThatIsNotRunYetAlreadyBlocksTheOthers() {
    var claim = instance(log(() -> {})).claim().orElseThrow();

    assertThat(instance(log(() -> {})).claim()).isEmpty();

    claim.run();
    assertThat(instance(log(() -> {})).claim().map(ReplayUseCase.Claim::run)).isPresent();
  }
}
