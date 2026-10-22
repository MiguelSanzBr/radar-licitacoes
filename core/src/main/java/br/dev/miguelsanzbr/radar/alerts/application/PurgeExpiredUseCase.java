package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import java.time.Clock;
import java.time.Duration;

/** Retention: idempotency keys live 24 h, unverified subscribers 7 days. */
public class PurgeExpiredUseCase {

  public static final Duration UNVERIFIED_RETENTION = Duration.ofDays(7);

  public record Purged(int idempotencyKeys, int subscribers) {}

  private final SubscriberRepository subscribers;
  private final IdempotencyStore idempotency;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public PurgeExpiredUseCase(
      SubscriberRepository subscribers,
      IdempotencyStore idempotency,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.subscribers = subscribers;
    this.idempotency = idempotency;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Purged run() {
    return unitOfWork.inTransaction(
        () -> {
          var now = clock.instant();
          return new Purged(
              idempotency.purgeBefore(now.minus(IdempotencyGuard.TTL)),
              subscribers.purgeUnverifiedBefore(now.minus(UNVERIFIED_RETENTION)));
        });
  }
}
