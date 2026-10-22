package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import java.time.Clock;
import java.util.UUID;

/** LGPD erasure: anonymize the subscriber, stop its searches, drop what is no longer needed. */
public class EraseSubscriberUseCase {

  private final SubscriberRepository subscribers;
  private final SavedSearchRepository searches;
  private final IdempotencyStore idempotency;
  private final VerificationTokenStore tokens;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public EraseSubscriberUseCase(
      SubscriberRepository subscribers,
      SavedSearchRepository searches,
      IdempotencyStore idempotency,
      VerificationTokenStore tokens,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.subscribers = subscribers;
    this.searches = searches;
    this.idempotency = idempotency;
    this.tokens = tokens;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public void erase(UUID subscriberId) {
    unitOfWork.run(
        () -> {
          var subscriber =
              subscribers.lockById(subscriberId).orElseThrow(UnauthenticatedException::new);
          subscribers.save(subscriber.erase(clock));
          searches.anonymizeAll(subscriberId);
          searches.deleteUnsentDeliveries(subscriberId);
          idempotency.deleteBySubscriber(subscriberId);
          tokens.deleteBySubscriber(subscriberId);
        });
  }
}
