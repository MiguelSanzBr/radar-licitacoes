package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertMatchedTest {

  private static final SearchMatch MATCH =
      new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), Frequency.INSTANT);

  @Test
  void carriesTheMatchAndTheFieldsTheEmailShows() {
    var facts = Facts.procurement().build();

    var event = AlertMatched.of(MATCH, facts);

    assertThat(event.savedSearchId()).isEqualTo(MATCH.savedSearchId());
    assertThat(event.subscriberId()).isEqualTo(MATCH.subscriberId());
    assertThat(event.procurementId()).isEqualTo(Facts.ID);
    assertThat(event.frequency()).isEqualTo(Frequency.INSTANT);
    assertThat(event.objectDescription()).isEqualTo(facts.objectDescription());
    assertThat(event.organName()).isEqualTo("Prefeitura de Exemplo");
    assertThat(event.uf()).isEqualTo("SC");
    assertThat(event.municipality()).isEqualTo("Exemplo");
    assertThat(event.estimatedValue()).isEqualTo(facts.estimatedValue());
    assertThat(event.proposalClosingAt()).isEqualTo(facts.closingAt());
  }

  @Test
  void theDetailPathReplacesTheSlashOfTheControlNumberLikeTheApiDoes() {
    assertThat(AlertMatched.of(MATCH, Facts.procurement().build()).detailPath())
        .isEqualTo("/licitacoes/83021808000182-1-000001-2026");
  }

  @Test
  void aDescriptionOfThreeHundredCharactersIsKeptWhole() {
    var text = "a".repeat(300);

    assertThat(
            AlertMatched.of(MATCH, Facts.procurement().description(text).build())
                .objectDescription())
        .isEqualTo(text);
  }

  @Test
  void aLongerDescriptionIsCutAtThreeHundredCharacters() {
    var text = "a".repeat(301);

    assertThat(
            AlertMatched.of(MATCH, Facts.procurement().description(text).build())
                .objectDescription())
        .isEqualTo("a".repeat(300));
  }

  @Test
  void theCutCountsCharactersNotUtf16UnitsSoItNeverSplitsASurrogatePair() {
    var text = "😀".repeat(301);

    var excerpt =
        AlertMatched.of(MATCH, Facts.procurement().description(text).build()).objectDescription();

    assertThat(excerpt.codePointCount(0, excerpt.length())).isEqualTo(300);
    assertThat(excerpt).isEqualTo("😀".repeat(300));
  }
}
