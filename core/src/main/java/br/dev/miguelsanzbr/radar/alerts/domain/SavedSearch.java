package br.dev.miguelsanzbr.radar.alerts.domain;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public record SavedSearch(
    UUID id,
    UUID subscriberId,
    String name,
    SavedQuery query,
    Frequency frequency,
    boolean active,
    Instant createdAt) {

  public static final int MAX_NAME = 80;

  public SavedSearch {
    if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
      throw new InvalidInputException("name must have 1 to 80 characters");
    }
  }

  public static SavedSearch create(
      Subscriber owner,
      String name,
      SavedQuery query,
      Frequency frequency,
      Clock clock,
      UuidV7 ids) {
    if (owner.erased()) {
      throw new SubscriberErasedException();
    }
    return new SavedSearch(ids.next(), owner.id(), name, query, frequency, true, clock.instant());
  }

  public SavedSearch deactivate() {
    return new SavedSearch(id, subscriberId, name, query, frequency, false, createdAt);
  }
}
