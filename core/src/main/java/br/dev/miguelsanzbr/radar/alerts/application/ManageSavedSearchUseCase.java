package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearchLimit;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public class ManageSavedSearchUseCase {

  private final SubscriberRepository subscribers;
  private final SavedSearchRepository searches;
  private final SavedSearchLimit limit;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final UuidV7 ids;

  public ManageSavedSearchUseCase(
      SubscriberRepository subscribers,
      SavedSearchRepository searches,
      SavedSearchLimit limit,
      UnitOfWork unitOfWork,
      Clock clock,
      UuidV7 ids) {
    this.subscribers = subscribers;
    this.searches = searches;
    this.limit = limit;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.ids = ids;
  }

  public SavedSearch create(UUID subscriberId, String name, SavedQuery query, Frequency frequency) {
    return unitOfWork.inTransaction(
        () -> {
          // The row lock serializes concurrent creates, so the limit cannot be overshot.
          var owner = subscribers.lockById(subscriberId).orElseThrow(UnauthenticatedException::new);
          limit.check(searches.countActive(subscriberId));
          var search = SavedSearch.create(owner, name, query, frequency, clock, ids);
          searches.save(search);
          return search;
        });
  }

  public SavedSearch get(UUID subscriberId, UUID id) {
    return searches
        .findActiveOwned(subscriberId, id)
        .orElseThrow(SavedSearchNotFoundException::new);
  }

  public List<SavedSearch> list(UUID subscriberId) {
    return searches.listActive(subscriberId);
  }

  public void delete(UUID subscriberId, UUID id) {
    unitOfWork.run(() -> searches.save(get(subscriberId, id).deactivate()));
  }
}
