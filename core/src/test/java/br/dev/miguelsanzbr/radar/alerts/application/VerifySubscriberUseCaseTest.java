package br.dev.miguelsanzbr.radar.alerts.application;

import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.CLOCK;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.DIRECT;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerifySubscriberUseCaseTest {

  @Mock SubscriberRepository subscribers;
  @Mock VerificationTokenStore tokens;

  VerifySubscriberUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new VerifySubscriberUseCase(subscribers, tokens, DIRECT, CLOCK);
  }

  @Test
  void validTokenMarksTheSubscriberVerified() {
    var subscriber = Fixtures.subscriber();
    when(tokens.consume(Sha256.hash("tok"), NOW)).thenReturn(Optional.of(subscriber.id()));
    when(subscribers.lockById(subscriber.id())).thenReturn(Optional.of(subscriber));

    useCase.verify("tok");

    verify(subscribers).save(subscriber.verify(CLOCK));
  }

  @Test
  void unknownOrExpiredTokenIsInvalid() {
    when(tokens.consume(Sha256.hash("tok"), NOW)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.verify("tok")).isInstanceOf(InvalidTokenException.class);

    verifyNoInteractions(subscribers);
  }
}
