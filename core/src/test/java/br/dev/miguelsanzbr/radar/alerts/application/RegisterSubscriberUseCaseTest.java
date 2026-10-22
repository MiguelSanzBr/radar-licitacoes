package br.dev.miguelsanzbr.radar.alerts.application;

import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.CLOCK;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.DIRECT;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.IDS;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.NOW;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.RANDOM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import br.dev.miguelsanzbr.radar.alerts.domain.InvalidInputException;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import br.dev.miguelsanzbr.radar.alerts.domain.Subscriber;
import br.dev.miguelsanzbr.radar.alerts.domain.VerificationToken;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegisterSubscriberUseCaseTest {

  @Mock SubscriberRepository subscribers;
  @Mock VerificationTokenStore tokens;
  @Mock VerificationMailer mailer;

  RegisterSubscriberUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase =
        new RegisterSubscriberUseCase(subscribers, tokens, mailer, DIRECT, CLOCK, IDS, RANDOM);
  }

  @Test
  void storesSubscriberAndOnlyTheHashOfTheTokenThenMailsTheRawToken() {
    var subscriber = useCase.register("Fulano@Example.com", true);

    assertThat(subscriber.email()).isEqualTo("fulano@example.com");
    assertThat(subscriber.consentAt()).isEqualTo(NOW);
    verify(subscribers).save(subscriber);
    var raw = ArgumentCaptor.forClass(String.class);
    verify(mailer).send(eq("fulano@example.com"), raw.capture());
    verify(tokens)
        .save(
            eq(Sha256.hash(raw.getValue())),
            eq(subscriber.id()),
            eq(NOW.plus(VerificationToken.TTL)));
  }

  @Test
  void withoutConsentNothingIsStoredOrSent() {
    assertThatThrownBy(() -> useCase.register("fulano@example.com", false))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("consent");

    verifyNoInteractions(subscribers, tokens, mailer);
  }

  @Test
  void invalidEmailIsRejectedBeforeStoring() {
    assertThatThrownBy(() -> useCase.register("not-an-email", true))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("not-an-email");

    verifyNoInteractions(subscribers, tokens, mailer);
  }

  @Test
  void duplicateEmailPropagatesAndNoMailIsSent() {
    org.mockito.Mockito.doThrow(new EmailAlreadyRegisteredException())
        .when(subscribers)
        .save(any(Subscriber.class));

    assertThatThrownBy(() -> useCase.register("fulano@example.com", true))
        .isInstanceOf(EmailAlreadyRegisteredException.class);

    verifyNoInteractions(mailer);
  }

  @Test
  void unverifiedAddressKeepsItsRowReplacesTheTokenAndIsMailedAgain() {
    var existing = Fixtures.subscriber();
    when(subscribers.lockByEmail("fulano@example.com")).thenReturn(Optional.of(existing));

    var result = useCase.register("Fulano@Example.com", true);

    assertThat(result).isEqualTo(existing);
    verify(subscribers, never()).save(any(Subscriber.class));
    verify(tokens).deleteBySubscriber(existing.id());
    var raw = ArgumentCaptor.forClass(String.class);
    verify(mailer).send(eq("fulano@example.com"), raw.capture());
    verify(tokens)
        .save(
            eq(Sha256.hash(raw.getValue())),
            eq(existing.id()),
            eq(NOW.plus(VerificationToken.TTL)));
  }

  @Test
  void verifiedAddressIsAConflictAndNothingIsSent() {
    var verified = Fixtures.subscriber().verify(CLOCK);
    when(subscribers.lockByEmail("fulano@example.com")).thenReturn(Optional.of(verified));

    assertThatThrownBy(() -> useCase.register("fulano@example.com", true))
        .isInstanceOf(EmailAlreadyRegisteredException.class);

    verifyNoInteractions(mailer, tokens);
  }
}
