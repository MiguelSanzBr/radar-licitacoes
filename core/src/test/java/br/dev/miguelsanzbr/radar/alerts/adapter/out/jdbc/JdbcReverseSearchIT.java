package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.search;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.subscriber;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.alerts.application.port.ReverseSearch;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcReverseSearchIT extends PostgresIT {

  @Autowired ReverseSearch reverseSearch;

  private UUID subscriber;

  @BeforeEach
  void aVerifiedSubscriber() {
    subscriber = subscriber(jdbc);
  }

  private List<UUID> matching(ProcurementFacts facts) {
    return reverseSearch.find(facts, Facts.NOW).stream().map(SearchMatch::savedSearchId).toList();
  }

  private List<UUID> matching() {
    return matching(Facts.procurement().build());
  }

  @Test
  void aSearchWhoseWordsAreInTheProcurementTextMatchesAndCarriesItsFrequency() {
    var id = search(jdbc, subscriber, "notebooks", "{}");

    assertThat(reverseSearch.find(Facts.procurement().build(), Facts.NOW))
        .containsExactly(new SearchMatch(id, subscriber, Frequency.INSTANT));
  }

  @Test
  void accentsAndCaseAreIgnoredLikeInTheSearchSide() {
    var accented = search(jdbc, subscriber, "educação", "{}");
    var upper = search(jdbc, subscriber, "NOTEBOOKS", "{}");

    assertThat(matching()).containsExactlyInAnyOrder(accented, upper);
  }

  @Test
  void theOrganUnitAndMunicipalityAreSearchableToo() {
    var organ = search(jdbc, subscriber, "prefeitura", "{}");
    var unit = search(jdbc, subscriber, "secretaria compras", "{}");
    var city = search(jdbc, subscriber, "exemplo", "{}");

    assertThat(matching()).containsExactlyInAnyOrder(organ, unit, city);
  }

  @Test
  void websearchSyntaxWorks() {
    var excluded = search(jdbc, subscriber, "notebooks -educacao", "{}");
    var either = search(jdbc, subscriber, "impressora or notebooks", "{}");
    var phrase = search(jdbc, subscriber, "\"secretaria de educacao\"", "{}");

    assertThat(matching()).containsExactlyInAnyOrder(either, phrase).doesNotContain(excluded);
  }

  @Test
  void aSearchWithAWordThatIsNotThereDoesNotMatch() {
    search(jdbc, subscriber, "impressora", "{}");

    assertThat(matching()).isEmpty();
  }

  @Test
  void anEmptyQueryIsAFilterOnlySearchThatMatchesEverythingThatPassesTheFilters() {
    var all = search(jdbc, subscriber, "", "{}");
    var inSc = search(jdbc, subscriber, "", "{\"uf\":\"SC\"}");
    search(jdbc, subscriber, "", "{\"uf\":\"SP\"}");

    assertThat(matching()).containsExactlyInAnyOrder(all, inSc);
  }

  @Test
  void aQueryOfOnlyWhitespaceIsAlsoAFilterOnlySearch() {
    var blank = search(jdbc, subscriber, "  \t ", "{}");
    var inSc = search(jdbc, subscriber, " ", "{\"uf\":\"SC\"}");
    search(jdbc, subscriber, " ", "{\"uf\":\"SP\"}");

    assertThat(matching()).containsExactlyInAnyOrder(blank, inSc);
  }

  @Test
  void aQueryMadeOnlyOfStopwordsNeverMatches() {
    search(jdbc, subscriber, "de a o para", "{}");

    assertThat(matching()).isEmpty();
  }

  @Test
  void ufAndIbgeMustEqualTheProcurementsOrTheProcurementHasNoneAndFails() {
    var uf = search(jdbc, subscriber, "notebooks", "{\"uf\":\"SC\"}");
    var ibge = search(jdbc, subscriber, "notebooks", "{\"ibge\":\"4205407\"}");
    search(jdbc, subscriber, "notebooks", "{\"uf\":\"SP\"}");
    search(jdbc, subscriber, "notebooks", "{\"ibge\":\"3550308\"}");

    assertThat(matching()).containsExactlyInAnyOrder(uf, ibge);
    assertThat(matching(Facts.procurement().uf(null).ibge(null).build())).isEmpty();
  }

  @Test
  void modalityMustEqual() {
    var same = search(jdbc, subscriber, "notebooks", "{\"modality\":6}");
    search(jdbc, subscriber, "notebooks", "{\"modality\":8}");

    assertThat(matching()).containsExactly(same);
    assertThat(matching(Facts.procurement().modality(null).build())).isEmpty();
  }

  @Test
  void theValueRangeIsInclusiveAndAnUnknownValueNeverSatisfiesIt() {
    var inside =
        search(
            jdbc, subscriber, "notebooks", "{\"minValue\":\"1000.00\",\"maxValue\":\"2000.00\"}");
    var exactMin = search(jdbc, subscriber, "notebooks", "{\"minValue\":\"1500.50\"}");
    var exactMax = search(jdbc, subscriber, "notebooks", "{\"maxValue\":\"1500.50\"}");
    search(jdbc, subscriber, "notebooks", "{\"minValue\":\"1500.51\"}");
    search(jdbc, subscriber, "notebooks", "{\"maxValue\":\"1500.49\"}");

    assertThat(matching()).containsExactlyInAnyOrder(inside, exactMin, exactMax);
    assertThat(matching(Facts.procurement().value(null).build())).isEmpty();
  }

  @Test
  void openRequiresTheProposalWindowToStillBeOpen() {
    var open = search(jdbc, subscriber, "notebooks", "{\"open\":true}");
    var notRestricted = search(jdbc, subscriber, "notebooks", "{\"open\":false}");

    assertThat(matching()).containsExactlyInAnyOrder(open, notRestricted);
    var closed = Facts.procurement().closingAt(Facts.NOW.minusSeconds(1)).build();
    assertThat(matching(closed)).containsExactly(notRestricted);
    assertThat(matching(Facts.procurement().closingAt(null).build()))
        .containsExactly(notRestricted);
    assertThat(matching(Facts.procurement().closingAt(Facts.NOW).build()))
        .as("closing exactly now is closed")
        .containsExactly(notRestricted);
  }

  @Test
  void filtersNeverStoredAreNotRestrictions() {
    var nulls =
        search(
            jdbc,
            subscriber,
            "notebooks",
            "{\"uf\":null,\"ibge\":null,\"modality\":null,\"minValue\":null,\"maxValue\":null,\"open\":null}");

    assertThat(matching()).containsExactly(nulls);
  }

  @Test
  void inactiveSearchesUnverifiedAndErasedSubscribersNeverMatch() {
    search(jdbc, subscriber, "notebooks", "{}", false);
    search(jdbc, subscriber(jdbc, false, false), "notebooks", "{}");
    search(jdbc, subscriber(jdbc, true, true), "notebooks", "{}");
    var live = search(jdbc, subscriber(jdbc), "notebooks", "{}");

    assertThat(matching()).containsExactly(live);
  }
}
