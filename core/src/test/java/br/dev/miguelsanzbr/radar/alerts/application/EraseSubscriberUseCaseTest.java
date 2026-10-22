package br.dev.miguelsanzbr.radar.alerts.application;

import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.CLOCK;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.DIRECT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EraseSubscriberUseCaseTest {

  @Mock SubscriberRepository subscribers;
  @Mock SavedSearchRepository searches;
  @Mock IdempotencyStore idempotency;
  @Mock VerificationTokenStore tokens;

  EraseSubscriberUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new EraseSubscriberUseCase(subscribers, searches, idempotency, tokens, DIRECT, CLOCK);
  }

  @Test
  void anonymizesAndCleansEverythingTiedToTheSubscriber() {
    var subscriber = Fixtures.subscriber();
    when(subscribers.lockById(subscriber.id())).thenReturn(Optional.of(subscriber));

    useCase.erase(subscriber.id());

    verify(subscribers).save(subscriber.erase(CLOCK));
    verify(searches).anonymizeAll(subscriber.id());
    verify(searches).deleteUnsentDeliveries(subscriber.id());
    verify(idempotency).deleteBySubscriber(subscriber.id());
    verify(tokens).deleteBySubscriber(subscriber.id());
  }

  @Test
  void unknownSubscriberIsUnauthenticated() {
    var id = UUID.randomUUID();
    when(subscribers.lockById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.erase(id)).isInstanceOf(UnauthenticatedException.class);

    verifyNoInteractions(searches, idempotency, tokens);
  }
}
