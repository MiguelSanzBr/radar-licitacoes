package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs an operation once per (subscriber, Idempotency-Key). The key row is taken inside the same
 * transaction as the operation, so a concurrent twin waits for the commit and then replays.
 */
public class IdempotencyGuard {

  public static final Duration TTL = Duration.ofHours(24);

  public record Stored(int status, String body) {}

  public record Result(Stored stored, boolean replayed) {}

  private final IdempotencyStore store;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public IdempotencyGuard(IdempotencyStore store, UnitOfWork unitOfWork, Clock clock) {
    this.store = store;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Result run(UUID subscriberId, String key, byte[] requestHash, Supplier<Stored> action) {
    return unitOfWork.inTransaction(
        () -> {
          var now = clock.instant();
          if (store.claim(subscriberId, key, requestHash, now, now.minus(TTL))) {
            var stored = action.get();
            store.complete(subscriberId, key, stored.status(), stored.body());
            return new Result(stored, false);
          }
          var recorded = store.find(subscriberId, key, requestHash).orElseThrow();
          if (!recorded.sameRequest()) {
            throw new IdempotencyMismatchException();
          }
          return new Result(new Stored(recorded.status(), recorded.body()), true);
        });
  }
}
