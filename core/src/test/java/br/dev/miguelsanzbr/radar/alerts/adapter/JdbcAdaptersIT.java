package br.dev.miguelsanzbr.radar.alerts.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import br.dev.miguelsanzbr.radar.alerts.application.EmailAlreadyRegisteredException;
import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore.Recorded;
import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertFilters;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import br.dev.miguelsanzbr.radar.alerts.domain.Subscriber;
import br.dev.miguelsanzbr.radar.shared.Email;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcAdaptersIT extends PostgresIT {

  private static final Clock CLOCK = Clock.systemUTC();
  private static final UuidV7 IDS = new UuidV7(CLOCK, new SecureRandom());

  @Autowired SubscriberRepository subscribers;
  @Autowired SavedSearchRepository searches;
  @Autowired VerificationTokenStore tokens;
  @Autowired IdempotencyStore idempotency;
  @Autowired UnitOfWork unitOfWork;

  private Subscriber newSubscriber(String email) {
    var subscriber = Subscriber.register(new Email(email), Instant.now(), CLOCK, IDS);
    subscribers.save(subscriber);
    return subscriber;
  }

  private SavedSearch newSearch(Subscriber owner, String name) {
    var filters = new AlertFilters("SP", "3550308", 6, "10.00", "99.50", true);
    var search =
        SavedSearch.create(
            owner, name, new SavedQuery("notebook", filters), Frequency.INSTANT, CLOCK, IDS);
    searches.save(search);
    return search;
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM alerts." + table).query(Long.class).single();
  }

  private static final org.assertj.core.data.TemporalUnitOffset TOLERANCE =
      within(1, ChronoUnit.MILLIS);

  private void backdate(Subscriber subscriber, Duration age) {
    jdbc.sql("UPDATE alerts.subscriber SET created_at = :at WHERE id = :id")
        .param("at", java.sql.Timestamp.from(Instant.now().minus(age)))
        .param("id", subscriber.id())
        .update();
  }

  @Test
  void subscriberRoundTripsThroughUpdates() {
    var subscriber = newSubscriber("fulano@example.com");
    var verified = subscriber.verify(CLOCK);
    subscribers.save(verified);
    subscribers.save(verified.erase(CLOCK));

    var loaded = subscribers.lockById(subscriber.id()).orElseThrow();

    assertThat(loaded.email()).isEqualTo("deleted-" + subscriber.id() + "@invalid");
    assertThat(loaded.verifiedAt()).isCloseTo(verified.verifiedAt(), TOLERANCE);
    assertThat(loaded.deletedAt()).isNotNull();
    assertThat(loaded.consentAt()).isCloseTo(subscriber.consentAt(), TOLERANCE);
    assertThat(loaded.createdAt()).isCloseTo(subscriber.createdAt(), TOLERANCE);
    assertThat(subscribers.lockById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void lockByEmailIgnoresCase() {
    var subscriber = newSubscriber("fulano@example.com");

    assertThat(subscribers.lockByEmail("FULANO@example.com"))
        .map(Subscriber::id)
        .contains(subscriber.id());
    assertThat(subscribers.lockByEmail("other@example.com")).isEmpty();
  }

  @Test
  void unverifiedSubscriberKeepsNullTimestamps() {
    var subscriber = newSubscriber("fulano@example.com");

    var loaded = subscribers.lockById(subscriber.id()).orElseThrow();

    assertThat(loaded.verifiedAt()).isNull();
    assertThat(loaded.deletedAt()).isNull();
  }

  @Test
  void emailsThatDifferOnlyByCaseCollideInTheDatabase() {
    jdbc.sql(
            "INSERT INTO alerts.subscriber (id, email, consent_at, created_at)"
                + " VALUES (:id, 'Fulano@Example.com', now(), now())")
        .param("id", UUID.randomUUID())
        .update();

    assertThatThrownBy(() -> newSubscriber("fulano@example.com"))
        .isInstanceOf(EmailAlreadyRegisteredException.class);
  }

  @Test
  void savedSearchKeepsFiltersAndOnlyActiveOnesAreVisible() {
    var owner = newSubscriber("fulano@example.com");
    var other = newSubscriber("ciclano@example.com");
    var first = newSearch(owner, "first");
    var second = newSearch(owner, "second");
    newSearch(other, "foreign");

    assertThat(searches.findActiveOwned(owner.id(), first.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.query()).isEqualTo(first.query());
              assertThat(found.frequency()).isEqualTo(Frequency.INSTANT);
              assertThat(found.createdAt()).isCloseTo(first.createdAt(), TOLERANCE);
            });
    assertThat(searches.findActiveOwned(other.id(), first.id())).isEmpty();
    assertThat(searches.countActive(owner.id())).isEqualTo(2);
    assertThat(searches.listActive(owner.id()))
        .extracting(SavedSearch::name)
        .containsExactlyInAnyOrder("first", "second");

    searches.save(second.deactivate());

    assertThat(searches.findActiveOwned(owner.id(), second.id())).isEmpty();
    assertThat(searches.countActive(owner.id())).isEqualTo(1);
  }

  @Test
  void anonymizeAllAndDeleteUnsentDeliveriesTouchOnlyTheSubscriber() {
    var owner = newSubscriber("fulano@example.com");
    var other = newSubscriber("ciclano@example.com");
    var mine = newSearch(owner, "mine");
    var theirs = newSearch(other, "theirs");
    for (var search : new SavedSearch[] {mine, theirs}) {
      jdbc.sql(
              "INSERT INTO alerts.alert_delivery (saved_search_id, procurement_id, matched_at, sent_at)"
                  + " VALUES (:s, 'p-sent', now(), now()), (:s, 'p-unsent', now(), NULL)")
          .param("s", search.id())
          .update();
    }

    searches.deleteUnsentDeliveries(owner.id());
    searches.anonymizeAll(owner.id());

    assertThat(searches.countActive(owner.id())).isZero();
    assertThat(searches.countActive(other.id())).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT name || '|' || query_text FROM alerts.saved_search WHERE subscriber_id = :s")
                .param("s", owner.id())
                .query(String.class)
                .list())
        .containsExactly("(apagada)|");
    assertThat(
            jdbc.sql("SELECT name FROM alerts.saved_search WHERE subscriber_id = :s")
                .param("s", other.id())
                .query(String.class)
                .single())
        .isEqualTo("theirs");
    assertThat(
            jdbc.sql("SELECT saved_search_id, procurement_id FROM alerts.alert_delivery")
                .query((rs, n) -> rs.getObject(1, UUID.class) + "/" + rs.getString(2))
                .list())
        .containsExactlyInAnyOrder(
            mine.id() + "/p-sent", theirs.id() + "/p-sent", theirs.id() + "/p-unsent");
  }

  @Test
  void tokenIsSingleUseAndExpires() {
    var subscriber = newSubscriber("fulano@example.com");
    var now = Instant.now();
    tokens.save(Sha256.hash("fresh"), subscriber.id(), now.plus(Duration.ofHours(48)));
    tokens.save(Sha256.hash("old"), subscriber.id(), now.minusSeconds(1));

    assertThat(tokens.consume(Sha256.hash("old"), now)).isEmpty();
    assertThat(tokens.consume(Sha256.hash("unknown"), now)).isEmpty();
    assertThat(tokens.consume(Sha256.hash("fresh"), now)).contains(subscriber.id());
    assertThat(tokens.consume(Sha256.hash("fresh"), now)).isEmpty();

    tokens.deleteBySubscriber(subscriber.id());

    assertThat(count("verification_token")).isZero();
  }

  @Test
  void idempotencyKeyIsClaimedOnceAndRecordsTheResponse() {
    var owner = newSubscriber("fulano@example.com");
    var now = Instant.now();
    var hash = Sha256.hash("body");
    var expired = now.minus(Duration.ofHours(24));

    assertThat(idempotency.claim(owner.id(), "key-1", hash, now, expired)).isTrue();
    assertThat(idempotency.claim(owner.id(), "key-1", hash, now, expired)).isFalse();
    idempotency.complete(owner.id(), "key-1", 201, "{\"a\":1}");

    assertThat(idempotency.find(owner.id(), "key-1", hash))
        .hasValueSatisfying(
            found -> {
              assertThat(found.sameRequest()).isTrue();
              assertThat(found.status()).isEqualTo(201);
              assertThat(found.body()).isEqualTo("{\"a\": 1}");
            });
    assertThat(idempotency.find(owner.id(), "key-1", Sha256.hash("other")))
        .map(Recorded::sameRequest)
        .contains(false);
    assertThat(idempotency.find(owner.id(), "missing", hash)).isEmpty();
  }

  @Test
  void idempotencyKeysAreScopedBySubscriber() {
    var one = newSubscriber("fulano@example.com");
    var two = newSubscriber("ciclano@example.com");
    var now = Instant.now();

    assertThat(idempotency.claim(one.id(), "same-key", Sha256.hash("a"), now, now.minusSeconds(60)))
        .isTrue();
    assertThat(idempotency.claim(two.id(), "same-key", Sha256.hash("a"), now, now.minusSeconds(60)))
        .isTrue();
  }

  @Test
  void expiredIdempotencyKeyCanBeClaimedAgain() {
    var owner = newSubscriber("fulano@example.com");
    var now = Instant.now();
    idempotency.claim(
        owner.id(),
        "key-1",
        Sha256.hash("a"),
        now.minus(Duration.ofHours(25)),
        now.minus(Duration.ofHours(49)));
    idempotency.complete(owner.id(), "key-1", 201, "{}");

    assertThat(
            idempotency.claim(
                owner.id(), "key-1", Sha256.hash("b"), now, now.minus(Duration.ofHours(24))))
        .isTrue();
  }

  @Test
  void idempotencyPurgeAndDeleteBySubscriber() {
    var one = newSubscriber("fulano@example.com");
    var two = newSubscriber("ciclano@example.com");
    var now = Instant.now();
    idempotency.claim(
        one.id(),
        "old",
        Sha256.hash("a"),
        now.minus(Duration.ofHours(30)),
        now.minus(Duration.ofDays(9)));
    idempotency.claim(one.id(), "new", Sha256.hash("a"), now, now.minus(Duration.ofDays(9)));
    idempotency.claim(two.id(), "other", Sha256.hash("a"), now, now.minus(Duration.ofDays(9)));

    assertThat(idempotency.purgeBefore(now.minus(Duration.ofHours(24)))).isEqualTo(1);
    idempotency.deleteBySubscriber(one.id());

    assertThat(jdbc.sql("SELECT key FROM alerts.idempotency_key").query(String.class).list())
        .containsExactly("other");
  }

  @Test
  void purgeRemovesOnlyOldUnverifiedSubscribersWithTheirData() {
    var stale = newSubscriber("stale@example.com");
    var staleSearch = newSearch(stale, "stale");
    jdbc.sql(
            "INSERT INTO alerts.alert_delivery (saved_search_id, procurement_id, matched_at)"
                + " VALUES (:s, 'p', now())")
        .param("s", staleSearch.id())
        .update();
    tokens.save(Sha256.hash("t"), stale.id(), Instant.now().plusSeconds(60));
    idempotency.claim(
        stale.id(), "k", Sha256.hash("a"), Instant.now(), Instant.now().minusSeconds(60));
    backdate(stale, Duration.ofDays(8));
    var verifiedOld = newSubscriber("verified@example.com");
    subscribers.save(verifiedOld.verify(CLOCK));
    backdate(verifiedOld, Duration.ofDays(30));
    newSubscriber("recent@example.com");

    var purged =
        unitOfWork.inTransaction(
            () -> subscribers.purgeUnverifiedBefore(Instant.now().minus(Duration.ofDays(7))));

    assertThat(purged).isEqualTo(1);
    assertThat(jdbc.sql("SELECT email FROM alerts.subscriber").query(String.class).list())
        .containsExactlyInAnyOrder("verified@example.com", "recent@example.com");
    assertThat(
            count("saved_search")
                + count("alert_delivery")
                + count("verification_token")
                + count("idempotency_key"))
        .isZero();
  }
}
