package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.delivery;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.namedSearch;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.subscriber;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcDeliveryLogIT extends PostgresIT {

  private static final Instant LATER = Facts.NOW.plusSeconds(90);

  @Autowired DeliveryLog log;

  private AlertMatched alert(UUID subscriber, UUID search, String id, Facts facts) {
    return AlertMatched.of(
        new SearchMatch(search, subscriber, Frequency.DAILY), facts.id(id).build());
  }

  private static final String P1 = "83021808000182-1-000001/2026";
  private static final String P2 = "83021808000182-1-000002/2026";

  @Test
  void findJoinsTheSubscriberAddressAndTheSearchName() {
    var subscriber = subscriber(jdbc);
    var search = namedSearch(jdbc, subscriber, "Notebooks SC", "INSTANT");
    delivery(jdbc, search, P1, Facts.NOW);

    var found = log.find(search, P1).orElseThrow();

    assertThat(found.email()).isEqualTo(subscriber + "@matcher.test");
    assertThat(found.searchName()).isEqualTo("Notebooks SC");
    assertThat(found.matchedAt()).isEqualTo(Facts.NOW);
    assertThat(found.sentAt()).isNull();
  }

  @Test
  void findIgnoresDeliveriesOfAnInactiveSearchAnErasedOrAnUnverifiedSubscriber() {
    var inactive = namedSearch(jdbc, subscriber(jdbc), "a", "INSTANT");
    delivery(jdbc, inactive, P1, Facts.NOW);
    jdbc.sql("UPDATE alerts.saved_search SET active = false WHERE id = :id")
        .param("id", inactive)
        .update();
    var erased = namedSearch(jdbc, subscriber(jdbc, true, true), "b", "INSTANT");
    delivery(jdbc, erased, P1, Facts.NOW);
    var unverified = namedSearch(jdbc, subscriber(jdbc, false, false), "c", "INSTANT");
    delivery(jdbc, unverified, P1, Facts.NOW);

    assertThat(log.find(inactive, P1)).isEmpty();
    assertThat(log.find(erased, P1)).isEmpty();
    assertThat(log.find(unverified, P1)).isEmpty();
    assertThat(log.find(UUID.randomUUID(), P1)).isEmpty();
  }

  @Test
  void markSentSetsTheTimeOnceAndNeverMovesIt() {
    var search = namedSearch(jdbc, subscriber(jdbc), "a", "INSTANT");
    delivery(jdbc, search, P1, Facts.NOW);

    log.markSent(search, P1, LATER);
    log.markSent(search, P1, LATER.plusSeconds(500));

    assertThat(log.find(search, P1).orElseThrow().sentAt()).isEqualTo(LATER);
  }

  @Test
  void theDigestQueueHoldsWhatWasKeptAndNotYetSentOrderedBySubscriberAndMatchTime() {
    var ana = subscriber(jdbc);
    var anaSearch = namedSearch(jdbc, ana, "Notebooks", "DAILY");
    delivery(jdbc, anaSearch, P2, Facts.NOW.plusSeconds(10));
    delivery(jdbc, anaSearch, P1, Facts.NOW);
    var full = alert(ana, anaSearch, P1, Facts.procurement());
    var sparse =
        alert(ana, anaSearch, P2, Facts.procurement().uf(null).value(null).closingAt(null));
    log.keepForDigest(sparse);
    log.keepForDigest(full);

    var queue = log.unsentDigest();

    assertThat(queue).hasSize(2);
    assertThat(queue.get(0).alert()).isEqualTo(full);
    assertThat(queue.get(1).alert()).isEqualTo(sparse);
    assertThat(queue.getFirst().subscriberId()).isEqualTo(ana);
    assertThat(queue.getFirst().email()).isEqualTo(ana + "@matcher.test");
    assertThat(queue.getFirst().searchName()).isEqualTo("Notebooks");
  }

  @Test
  void theDigestQueueSkipsSentRowsRowsNeverKeptAndRowsOfPeopleWhoCannotReceive() {
    var ana = subscriber(jdbc);
    var sent = namedSearch(jdbc, ana, "sent", "DAILY");
    var notKept = namedSearch(jdbc, ana, "notKept", "DAILY");
    var inactive = namedSearch(jdbc, ana, "inactive", "DAILY");
    var erased = namedSearch(jdbc, subscriber(jdbc, true, true), "erased", "DAILY");
    var unverified = namedSearch(jdbc, subscriber(jdbc, false, false), "unverified", "DAILY");
    for (var search : new UUID[] {sent, notKept, inactive, erased, unverified}) {
      delivery(jdbc, search, P1, Facts.NOW);
    }
    for (var search : new UUID[] {sent, inactive, erased, unverified}) {
      log.keepForDigest(alert(UUID.randomUUID(), search, P1, Facts.procurement()));
    }
    log.markSent(sent, P1, LATER);
    jdbc.sql("UPDATE alerts.saved_search SET active = false WHERE id = :id")
        .param("id", inactive)
        .update();

    assertThat(log.unsentDigest()).isEmpty();
  }

  @Test
  void markingSentClearsWhatWasKeptForTheDigest() {
    var ana = subscriber(jdbc);
    var search = namedSearch(jdbc, ana, "a", "DAILY");
    delivery(jdbc, search, P1, Facts.NOW);
    log.keepForDigest(alert(ana, search, P1, Facts.procurement()));
    assertThat(keptColumns(search))
        .containsEntry("detail_path", "/licitacoes/83021808000182-1-000001-2026");

    log.markSent(search, P1, LATER);

    assertThat(keptColumns(search).values()).containsOnlyNulls();
    assertThat(log.find(search, P1).orElseThrow().sentAt()).isEqualTo(LATER);
  }

  private Map<String, Object> keptColumns(UUID search) {
    return jdbc.sql(
            "SELECT object_description, organ_name, uf, municipality, estimated_value,"
                + " proposal_closing_at, detail_path FROM alerts.alert_delivery"
                + " WHERE saved_search_id = :search")
        .param("search", search)
        .query()
        .singleRow();
  }

  @Test
  void keepingAfterTheSendDoesNotQueueTheRowAgain() {
    var ana = subscriber(jdbc);
    var search = namedSearch(jdbc, ana, "a", "DAILY");
    delivery(jdbc, search, P1, Facts.NOW);
    log.markSent(search, P1, LATER);

    log.keepForDigest(alert(ana, search, P1, Facts.procurement()));

    assertThat(log.unsentDigest()).isEmpty();
  }
}
