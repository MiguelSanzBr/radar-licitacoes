package br.dev.miguelsanzbr.radar.search.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Rebuilds the read model from the event log. The live consumer is stopped for the duration so it
 * does not race the rebuild, and started again at the end (if it was running), from the offsets its
 * group had committed: whatever it re-reads of what the replay projected is dropped by the inbox.
 * Only one replay runs cluster-wide: {@link #claim()} takes the lock for the whole run.
 */
public class ReplayUseCase {

  static final int BATCH_SIZE = 500;

  private final ProcurementProjector projector;
  private final LiveProjection live;
  private final EventLog log;
  private final Clock clock;
  private final ReplayLock lock;

  /** A replay that holds the cluster-wide lock until {@link #run()} ends. */
  public interface Claim {
    ReplayReport run();
  }

  public ReplayUseCase(
      ProcurementProjector projector,
      LiveProjection live,
      EventLog log,
      Clock clock,
      ReplayLock lock) {
    this.projector = projector;
    this.live = live;
    this.log = log;
    this.clock = clock;
    this.lock = lock;
  }

  /**
   * Takes the lock, or returns empty when another instance is replaying. The caller must run the
   * claim, which releases the lock when it ends, however it ends.
   */
  public Optional<Claim> claim() {
    return lock.tryAcquire()
        .map(
            held ->
                () -> {
                  try (held) {
                    return replay();
                  }
                });
  }

  private ReplayReport replay() {
    var startedAt = clock.instant();
    var wasRunning = live.isRunning();
    try {
      if (wasRunning) {
        live.stop();
      }
      return rebuild(startedAt);
    } finally {
      // why: a listener that an operator had stopped stays stopped
      if (wasRunning) {
        live.start();
      }
    }
  }

  private ReplayReport rebuild(Instant startedAt) {
    try (var snapshot = log.snapshot()) {
      projector.reset();
      var events = snapshot.replay(BATCH_SIZE, projector::projectAll);
      return ReplayReport.of(events, startedAt, clock.instant());
    }
  }
}
