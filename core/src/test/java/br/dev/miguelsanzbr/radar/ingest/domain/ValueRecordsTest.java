package br.dev.miguelsanzbr.radar.ingest.domain;

import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueRecordsTest {

  @Test
  void organAcceptsFourteenDigits() {
    assertThat(new Organ("83021808000182", "Prefeitura").cnpj()).isEqualTo("83021808000182");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "1234567890123", "123456789012345", "8302180800018x", "83.021.808/0001-82"})
  void organRejectsMalformedCnpj(String cnpj) {
    assertThatIllegalArgumentException().isThrownBy(() -> new Organ(cnpj, "Prefeitura"));
  }

  @Test
  void organRequiresCnpjAndName() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Organ(null, "Prefeitura"));
    assertThatNullPointerException().isThrownBy(() -> new Organ("83021808000182", null));
  }

  @Test
  void organUnitAllowsMissingLocation() {
    var unit = new OrganUnit("1010", "Secretaria", null, null, null);
    assertThat(unit.uf()).isNull();
    assertThat(unit.municipalityName()).isNull();
    assertThat(unit.ibgeCode()).isNull();
  }

  @Test
  void organUnitKeepsLocation() {
    var unit = new OrganUnit("1010", "Secretaria", Uf.SC, "Exemplo", new IbgeCode("4205407"));
    assertThat(unit.uf()).isEqualTo(Uf.SC);
    assertThat(unit.municipalityName()).isEqualTo("Exemplo");
    assertThat(unit.ibgeCode()).isEqualTo(new IbgeCode("4205407"));
  }

  @Test
  void organUnitRequiresCodeAndName() {
    assertThatNullPointerException()
        .isThrownBy(() -> new OrganUnit(null, "Secretaria", null, null, null));
    assertThatNullPointerException()
        .isThrownBy(() -> new OrganUnit("1010", null, null, null, null));
  }

  @Test
  void catalogRecordsRequireAName() {
    assertThatNullPointerException().isThrownBy(() -> new Modality(6, null));
    assertThatNullPointerException().isThrownBy(() -> new DisputeMode(1, null));
    assertThatNullPointerException().isThrownBy(() -> new LegalBasis(1, null));
    assertThatNullPointerException().isThrownBy(() -> new PncpSituation(1, null));
  }

  @Test
  void catalogRecordsKeepTheirValues() {
    assertThat(new Modality(6, "Pregao").id()).isEqualTo(6);
    assertThat(new DisputeMode(1, "Aberto").id()).isEqualTo(1);
    assertThat(new LegalBasis(2, "Lei").code()).isEqualTo(2);
    assertThat(new PncpSituation(3, "Anulada").id()).isEqualTo(3);
  }

  @Test
  void windowRejectsClosingBeforeOpening() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ProposalWindow(T0, T0.minusSeconds(1)));
  }

  @Test
  void windowAcceptsEqualOrPartialBounds() {
    assertThatCode(() -> new ProposalWindow(T0, T0)).doesNotThrowAnyException();
    assertThatCode(() -> new ProposalWindow(T0, T0.plusSeconds(1))).doesNotThrowAnyException();
    assertThatCode(() -> new ProposalWindow(T0, null)).doesNotThrowAnyException();
    assertThatCode(() -> new ProposalWindow(null, T0)).doesNotThrowAnyException();
    assertThatCode(() -> new ProposalWindow(null, null)).doesNotThrowAnyException();
  }

  private static ProposalPhase phase(Instant opening, Instant closing, Instant now) {
    return new ProposalWindow(opening, closing).phaseAt(now);
  }

  @Test
  void phaseWithBothBounds() {
    var open = T0;
    var close = T0.plusSeconds(100);
    assertThat(phase(open, close, open.minusSeconds(1))).isEqualTo(ProposalPhase.UPCOMING);
    assertThat(phase(open, close, open)).isEqualTo(ProposalPhase.OPEN);
    assertThat(phase(open, close, close.minusSeconds(1))).isEqualTo(ProposalPhase.OPEN);
    assertThat(phase(open, close, close)).isEqualTo(ProposalPhase.CLOSED);
    assertThat(phase(open, close, close.plusSeconds(1))).isEqualTo(ProposalPhase.CLOSED);
  }

  @Test
  void phaseWithOnlyOpening() {
    assertThat(phase(T0, null, T0.minusSeconds(1))).isEqualTo(ProposalPhase.UPCOMING);
    assertThat(phase(T0, null, T0)).isEqualTo(ProposalPhase.OPEN);
    assertThat(phase(T0, null, T0.plusSeconds(1))).isEqualTo(ProposalPhase.OPEN);
  }

  @Test
  void phaseWithOnlyClosing() {
    assertThat(phase(null, T0, T0.minusSeconds(1))).isEqualTo(ProposalPhase.OPEN);
    assertThat(phase(null, T0, T0)).isEqualTo(ProposalPhase.CLOSED);
  }

  @Test
  void phaseWithoutBoundsIsUnknown() {
    assertThat(phase(null, null, T0)).isEqualTo(ProposalPhase.UNKNOWN);
  }

  @Test
  void emptyIntervalIsClosedOnceReached() {
    assertThat(phase(T0, T0, T0)).isEqualTo(ProposalPhase.CLOSED);
    assertThat(phase(T0, T0, T0.minusSeconds(1))).isEqualTo(ProposalPhase.UPCOMING);
  }

  @Test
  void historyEntryRequiresInstants() {
    assertThatNullPointerException()
        .isThrownBy(() -> new SituationHistoryEntry(null, 1, false, null, T0));
    assertThatNullPointerException()
        .isThrownBy(() -> new SituationHistoryEntry(null, 1, false, T0, null));
    assertThat(new SituationHistoryEntry(2, 1, true, T0, T0).fromSituationId()).isEqualTo(2);
  }
}
