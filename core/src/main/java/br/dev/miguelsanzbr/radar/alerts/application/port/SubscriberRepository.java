package br.dev.miguelsanzbr.radar.alerts.application.port;

import br.dev.miguelsanzbr.radar.alerts.domain.Subscriber;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubscriberRepository {

  /**
   * Inserts or updates; throws {@code EmailAlreadyRegisteredException} when the e-mail or the
   * account already belongs to another subscriber.
   */
  void save(Subscriber subscriber);

  /** Loads the subscriber and holds its row lock until the transaction ends. */
  Optional<Subscriber> lockById(UUID id);

  /** Subscriber linked to the identity provider account, without locking. */
  Optional<UUID> findIdBySubject(String subject);

  /** Same lock, found by e-mail (case-insensitive). */
  Optional<Subscriber> lockByEmail(String email);

  /** Hard-deletes never-verified subscribers created before the cutoff, with their data. */
  int purgeUnverifiedBefore(Instant cutoff);
}
