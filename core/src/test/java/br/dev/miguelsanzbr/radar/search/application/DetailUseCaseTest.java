package br.dev.miguelsanzbr.radar.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DetailUseCaseTest {

  static ProcurementDetail detail(String id, List<SituationChange> history) {
    return new ProcurementDetail(
        id,
        "Aquisição de computadores",
        "12345678000190",
        "Prefeitura de Teste",
        "1",
        "Secretaria de Saúde",
        "SP",
        "São Paulo",
        "3550308",
        6,
        "Pregão Eletrônico",
        null,
        null,
        false,
        "PUBLISHED",
        Money.of("1500.00"),
        null,
        null,
        null,
        Instant.parse("2026-10-20T12:00:00Z"),
        Instant.parse("2026-10-20T12:00:00Z"),
        history,
        "abc123");
  }

  @Test
  void theStoredDetailComesBack() {
    var stored = detail("12345678000190-1-000123/2026", List.of());
    DetailReadModel readModel =
        id -> id.equals(stored.id()) ? Optional.of(stored) : Optional.empty();

    assertThat(new DetailUseCase(readModel).find("12345678000190-1-000123/2026")).isEqualTo(stored);
  }

  @Test
  void anUnknownIdIsNotFound() {
    var useCase = new DetailUseCase(id -> Optional.empty());

    assertThatThrownBy(() -> useCase.find("12345678000190-1-000123/2026"))
        .isInstanceOf(ProcurementNotFoundException.class)
        .hasMessageContaining("12345678000190-1-000123/2026");
  }

  @Test
  void theDetailKeepsItsHistoryImmutable() {
    var change = new SituationChange(null, "Divulgada no PNCP", Instant.EPOCH, false);
    var history = new ArrayList<>(List.of(change));
    var detail = detail("id", history);
    history.clear();

    assertThat(detail.history()).containsExactly(change);
  }
}
