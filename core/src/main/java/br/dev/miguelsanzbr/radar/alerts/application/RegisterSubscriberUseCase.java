package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import br.dev.miguelsanzbr.radar.alerts.domain.InvalidInputException;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import br.dev.miguelsanzbr.radar.alerts.domain.Subscriber;
import br.dev.miguelsanzbr.radar.alerts.domain.VerificationToken;
import br.dev.miguelsanzbr.radar.shared.Email;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.util.random.RandomGenerator;

public class RegisterSubscriberUseCase {

  private final SubscriberRepository subscribers;
  private final VerificationTokenStore tokens;
  private final VerificationMailer mailer;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final UuidV7 ids;
  private final RandomGenerator random;

  public RegisterSubscriberUseCase(
      SubscriberRepository subscribers,
      VerificationTokenStore tokens,
      VerificationMailer mailer,
      UnitOfWork unitOfWork,
      Clock clock,
      UuidV7 ids,
      RandomGenerator random) {
    this.subscribers = subscribers;
    this.tokens = tokens;
    this.mailer = mailer;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.ids = ids;
    this.random = random;
  }

  /**
   * An address that never verified keeps its row and gets a fresh token (so a lost mail can be
   * asked for again); a verified one is a conflict.
   */
  public Subscriber register(String email, boolean consent) {
    var now = clock.instant();
    var candidate =
        Subscriber.register(
            InvalidInputException.guard(() -> new Email(email)), consent ? now : null, clock, ids);
    var rawToken = VerificationToken.newRaw(random);
    var subscriber =
        unitOfWork.inTransaction(
            () -> {
              var existing = subscribers.lockByEmail(candidate.email());
              if (existing.isPresent() && existing.get().verifiedAt() != null) {
                throw new EmailAlreadyRegisteredException();
              }
              var target = existing.orElse(candidate);
              if (existing.isPresent()) {
                tokens.deleteBySubscriber(target.id());
              } else {
                subscribers.save(target);
              }
              tokens.save(Sha256.hash(rawToken), target.id(), now.plus(VerificationToken.TTL));
              return target;
            });
    // After commit: a mail failure must not roll back the registration.
    mailer.send(subscriber.email(), rawToken);
    return subscriber;
  }
}
