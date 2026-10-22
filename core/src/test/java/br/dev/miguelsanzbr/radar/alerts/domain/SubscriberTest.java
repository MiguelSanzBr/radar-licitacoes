package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.shared.Email;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SubscriberTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UuidV7 IDS = new UuidV7(CLOCK, RandomGenerator.of("L64X128MixRandom"));

  private static Subscriber registered() {
    return Subscriber.register(new Email("Fulano@Example.com"), NOW.minusSeconds(5), CLOCK, IDS);
  }

  @Test
  void registerStartsUnverifiedWithConsentAndCreationTime() {
    var subscriber = registered();

    assertThat(subscriber.email()).isEqualTo("fulano@example.com");
    assertThat(subscriber.consentAt()).isEqualTo(NOW.minusSeconds(5));
    assertThat(subscriber.createdAt()).isEqualTo(NOW);
    assertThat(subscriber.verifiedAt()).isNull();
    assertThat(subscriber.deletedAt()).isNull();
    assertThat(subscriber.id().version()).isEqualTo(7);
  }

  @Test
  void registerWithoutConsentIsRejected() {
    assertThatThrownBy(() -> Subscriber.register(new Email("a@b.co"), null, CLOCK, IDS))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("consent");
  }

  @Test
  void verifyStampsTheClockTime() {
    assertThat(registered().verify(CLOCK).verifiedAt()).isEqualTo(NOW);
  }

  @Test
  void eraseAnonymizesTheEmailAndStampsDeletion() {
    var subscriber = registered();

    var erased = subscriber.erase(CLOCK);

    assertThat(erased.email()).isEqualTo("deleted-" + subscriber.id() + "@invalid");
    assertThat(erased.deletedAt()).isEqualTo(NOW);
    assertThat(erased.id()).isEqualTo(subscriber.id());
    assertThat(erased.erased()).isTrue();
    assertThat(subscriber.erased()).isFalse();
  }

  @Test
  void eraseTwiceKeepsTheFirstDeletionTime() {
    var first = registered().erase(CLOCK);

    var second = first.erase(Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

    assertThat(second).isEqualTo(first);
  }

  @Test
  void erasedSubscriberCannotVerify() {
    var erased = registered().erase(CLOCK);

    assertThatThrownBy(() -> erased.verify(CLOCK)).isInstanceOf(SubscriberErasedException.class);
  }
}
