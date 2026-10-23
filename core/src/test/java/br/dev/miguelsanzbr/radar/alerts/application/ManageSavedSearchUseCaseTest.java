package br.dev.miguelsanzbr.radar.alerts.application;

import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.CLOCK;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.DIRECT;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.IDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertFilters;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearchLimit;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearchLimitExceededException;
import br.dev.miguelsanzbr.radar.alerts.domain.SubscriberErasedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManageSavedSearchUseCaseTest {

  private static final SavedQuery QUERY = new SavedQuery("notebook", AlertFilters.NONE);

  @Mock SubscriberRepository subscribers;
  @Mock SavedSearchRepository searches;

  ManageSavedSearchUseCase useCase;
  boolean limited = true;

  @BeforeEach
  void setUp() {
    useCase =
        new ManageSavedSearchUseCase(
            subscribers, searches, new SavedSearchLimit(20), () -> limited, DIRECT, CLOCK, IDS);
  }

  @Test
  void createSavesAnActiveSearchOfTheOwner() {
    var owner = Fixtures.subscriber();
    when(subscribers.lockById(owner.id())).thenReturn(Optional.of(owner));
    when(searches.countActive(owner.id())).thenReturn(19L);

    var created = useCase.create(owner.id(), "Notebooks", QUERY, Frequency.INSTANT);

    assertThat(created.subscriberId()).isEqualTo(owner.id());
    assertThat(created.active()).isTrue();
    verify(searches).save(created);
  }

  @Test
  void createBeyondTheLimitIsRejected() {
    var owner = Fixtures.subscriber();
    when(subscribers.lockById(owner.id())).thenReturn(Optional.of(owner));
    when(searches.countActive(owner.id())).thenReturn(20L);

    assertThatThrownBy(() -> useCase.create(owner.id(), "x", QUERY, Frequency.DAILY))
        .isInstanceOf(SavedSearchLimitExceededException.class);

    verify(searches, never()).save(any());
  }

  @Test
  void withTheLimitFlagOffTheLimitIsNotEnforced() {
    limited = false;
    var owner = Fixtures.subscriber();
    when(subscribers.lockById(owner.id())).thenReturn(Optional.of(owner));

    var created = useCase.create(owner.id(), "x", QUERY, Frequency.DAILY);

    verify(searches).save(created);
    verify(searches, never()).countActive(any());
  }

  @Test
  void createForUnknownSubscriberIsUnauthenticated() {
    var id = UUID.randomUUID();
    when(subscribers.lockById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.create(id, "x", QUERY, Frequency.DAILY))
        .isInstanceOf(UnauthenticatedException.class);
  }

  @Test
  void createForErasedSubscriberIsRejected() {
    var erased = Fixtures.subscriber().erase(CLOCK);
    when(subscribers.lockById(erased.id())).thenReturn(Optional.of(erased));
    when(searches.countActive(erased.id())).thenReturn(0L);

    assertThatThrownBy(() -> useCase.create(erased.id(), "x", QUERY, Frequency.DAILY))
        .isInstanceOf(SubscriberErasedException.class);
  }

  @Test
  void getReturnsAnOwnedActiveSearch() {
    var search = existing();
    when(searches.findActiveOwned(search.subscriberId(), search.id()))
        .thenReturn(Optional.of(search));

    assertThat(useCase.get(search.subscriberId(), search.id())).isEqualTo(search);
  }

  @Test
  void getOfAnotherSubscribersSearchIsNotFound() {
    var stranger = UUID.randomUUID();
    var id = UUID.randomUUID();
    when(searches.findActiveOwned(stranger, id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.get(stranger, id))
        .isInstanceOf(SavedSearchNotFoundException.class);
  }

  @Test
  void listReturnsTheActiveSearchesOfTheSubscriber() {
    var search = existing();
    when(searches.listActive(search.subscriberId())).thenReturn(List.of(search));

    assertThat(useCase.list(search.subscriberId())).containsExactly(search);
  }

  @Test
  void deleteDeactivatesTheOwnedSearch() {
    var search = existing();
    when(searches.findActiveOwned(search.subscriberId(), search.id()))
        .thenReturn(Optional.of(search));

    useCase.delete(search.subscriberId(), search.id());

    verify(searches).save(search.deactivate());
  }

  @Test
  void deleteOfAnotherSubscribersSearchIsNotFoundAndChangesNothing() {
    var stranger = UUID.randomUUID();
    var id = UUID.randomUUID();
    when(searches.findActiveOwned(stranger, id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.delete(stranger, id))
        .isInstanceOf(SavedSearchNotFoundException.class);

    verify(searches, never()).save(any());
  }

  private static SavedSearch existing() {
    return SavedSearch.create(
        Fixtures.subscriber(), "Notebooks", QUERY, Frequency.DAILY, CLOCK, IDS);
  }
}
