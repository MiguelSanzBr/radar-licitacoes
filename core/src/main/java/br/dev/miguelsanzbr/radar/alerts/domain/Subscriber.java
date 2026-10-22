package br.dev.miguelsanzbr.radar.alerts.domain;

import br.dev.miguelsanzbr.radar.shared.Email;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * A person who agreed to receive e-mail alerts. The e-mail is plain text because an erased
 * subscriber keeps a placeholder address that is not deliverable on purpose. {@code
 * externalSubject} is the identity provider account that gave consent.
 */
public record Subscriber(
    UUID id,
    String email,
    Instant consentAt,
    Instant verifiedAt,
    Instant deletedAt,
    Instant createdAt,
    String externalSubject) {

  public static Subscriber register(Email email, Instant consentAt, Clock clock, UuidV7 ids) {
    if (consentAt == null) {
      throw new InvalidInputException("consent is required");
    }
    return new Subscriber(ids.next(), email.value(), consentAt, null, null, clock.instant(), null);
  }

  public Subscriber verify(Clock clock) {
    if (erased()) {
      throw new SubscriberErasedException();
    }
    return new Subscriber(
        id, email, consentAt, clock.instant(), deletedAt, createdAt, externalSubject);
  }

  public Subscriber withSubject(String subject) {
    return new Subscriber(id, email, consentAt, verifiedAt, deletedAt, createdAt, subject);
  }

  /**
   * Anonymizes the address and unlinks the account but keeps the row, so delivery history stays
   * consistent and the account may consent again as a new subscriber.
   */
  public Subscriber erase(Clock clock) {
    if (erased()) {
      return this;
    }
    return new Subscriber(
        id, "deleted-" + id + "@invalid", consentAt, verifiedAt, clock.instant(), createdAt, null);
  }

  public boolean erased() {
    return deletedAt != null;
  }
}
