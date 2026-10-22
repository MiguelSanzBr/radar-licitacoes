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

class SavedSearchTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UuidV7 IDS = new UuidV7(CLOCK, RandomGenerator.of("L64X128MixRandom"));
  private static final Subscriber SUBSCRIBER =
      Subscriber.register(new Email("a@b.co"), NOW, CLOCK, IDS);
  private static final SavedQuery QUERY = new SavedQuery("notebook", AlertFilters.NONE);

  private static SavedSearch create(String name, SavedQuery query) {
    return SavedSearch.create(SUBSCRIBER, name, query, Frequency.DAILY, CLOCK, IDS);
  }

  @Test
  void createStartsActiveForTheSubscriber() {
    var search = create("Notebooks", QUERY);

    assertThat(search.subscriberId()).isEqualTo(SUBSCRIBER.id());
    assertThat(search.name()).isEqualTo("Notebooks");
    assertThat(search.query()).isEqualTo(QUERY);
    assertThat(search.frequency()).isEqualTo(Frequency.DAILY);
    assertThat(search.active()).isTrue();
    assertThat(search.createdAt()).isEqualTo(NOW);
  }

  @Test
  void nameAcceptsOneToEightyCharacters() {
    assertThat(create("a", QUERY).name()).isEqualTo("a");
    assertThat(create("a".repeat(80), QUERY).name()).hasSize(80);
  }

  @Test
  void nameOutsideTheBoundsIsRejected() {
    assertThatThrownBy(() -> create("", QUERY)).isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> create("a".repeat(81), QUERY))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> create(null, QUERY)).isInstanceOf(InvalidInputException.class);
  }

  @Test
  void queryTextAcceptsUpToFiveHundredCharacters() {
    assertThat(new SavedQuery("a".repeat(500), AlertFilters.NONE).text()).hasSize(500);
    assertThat(new SavedQuery("", AlertFilters.NONE).text()).isEmpty();
  }

  @Test
  void queryTextOverTheLimitOrNullIsRejected() {
    assertThatThrownBy(() -> new SavedQuery("a".repeat(501), AlertFilters.NONE))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> new SavedQuery(null, AlertFilters.NONE))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> new SavedQuery("x", null)).isInstanceOf(InvalidInputException.class);
  }

  @Test
  void erasedSubscriberCannotCreateSearches() {
    var erased = SUBSCRIBER.erase(CLOCK);

    assertThatThrownBy(() -> SavedSearch.create(erased, "x", QUERY, Frequency.INSTANT, CLOCK, IDS))
        .isInstanceOf(SubscriberErasedException.class);
  }

  @Test
  void deactivateKeepsEverythingElse() {
    var search = create("Notebooks", QUERY);

    var inactive = search.deactivate();

    assertThat(inactive.active()).isFalse();
    assertThat(inactive).usingRecursiveComparison().ignoringFields("active").isEqualTo(search);
    assertThat(search.active()).isTrue();
  }
}
