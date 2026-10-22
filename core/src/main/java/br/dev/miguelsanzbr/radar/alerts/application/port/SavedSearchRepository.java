package br.dev.miguelsanzbr.radar.alerts.application.port;

import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedSearchRepository {

  void save(SavedSearch search);

  /** Empty when the id is unknown, inactive or belongs to another subscriber. */
  Optional<SavedSearch> findActiveOwned(UUID subscriberId, UUID id);

  List<SavedSearch> listActive(UUID subscriberId);

  long countActive(UUID subscriberId);

  /** Deactivates every search of the subscriber and blanks its name and text. */
  void anonymizeAll(UUID subscriberId);

  void deleteUnsentDeliveries(UUID subscriberId);
}
