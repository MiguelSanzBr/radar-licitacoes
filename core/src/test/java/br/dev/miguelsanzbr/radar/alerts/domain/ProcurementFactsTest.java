package br.dev.miguelsanzbr.radar.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import org.junit.jupiter.api.Test;

class ProcurementFactsTest {

  @Test
  void theSearchableTextJoinsDescriptionOrganUnitAndMunicipality() {
    assertThat(Facts.procurement().build().searchableText())
        .isEqualTo(
            "Aquisicao de notebooks para a secretaria de educacao Prefeitura de Exemplo"
                + " Secretaria de Compras Exemplo");
  }

  @Test
  void anAbsentMunicipalityIsLeftOut() {
    var facts =
        new ProcurementFacts("1", "objeto", "orgao", "unidade", null, null, null, null, null, null);

    assertThat(facts.searchableText()).isEqualTo("objeto orgao unidade");
  }

  @Test
  void idDescriptionOrganAndUnitAreRequired() {
    assertThatThrownBy(
            () -> new ProcurementFacts(null, "o", "g", "u", null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new ProcurementFacts("1", null, "g", "u", null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new ProcurementFacts("1", "o", null, "u", null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new ProcurementFacts("1", "o", "g", null, null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }
}
