package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import java.time.Clock;

public class VerifySubscriberUseCase {

  private final SubscriberRepository subscribers;
  private final VerificationTokenStore tokens;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public VerifySubscriberUseCase(
      SubscriberRepository subscribers,
      VerificationTokenStore tokens,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.subscribers = subscribers;
    this.tokens = tokens;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public void verify(String rawToken) {
    unitOfWork.run(
        () -> {
          var id =
              tokens
                  .consume(Sha256.hash(rawToken), clock.instant())
                  .orElseThrow(InvalidTokenException::new);
          subscribers.save(subscribers.lockById(id).orElseThrow().verify(clock));
        });
  }
}
