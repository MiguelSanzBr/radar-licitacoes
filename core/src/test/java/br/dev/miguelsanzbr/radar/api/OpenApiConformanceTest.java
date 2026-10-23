package br.dev.miguelsanzbr.radar.api;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.answerConforms;
import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.api.generated.api.ProcurementsApi;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementDetail;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementPage;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementStatus;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementSummary;
import br.dev.miguelsanzbr.radar.api.generated.model.ProposalPhase;
import br.dev.miguelsanzbr.radar.api.generated.model.SituationHistory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

class OpenApiConformanceTest {

  private static ProcurementSummary summary() {
    return new ProcurementSummary()
        .id("12345678000190-1-000123-2026")
        .objectDescription("Aquisicao de computadores")
        .organName("Prefeitura Municipal de Exemplo")
        .modalityId(6)
        .status(ProcurementStatus.PUBLISHED)
        .proposalPhase(ProposalPhase.OPEN)
        .publishedAt(OffsetDateTime.parse("2026-10-22T12:00:00Z"));
  }

  /** Controlador de teste sobre a interface gerada: so a rota de busca responde. */
  @RestController
  static class FixedProcurements implements ProcurementsApi {

    private final ProcurementPage page;

    FixedProcurements(ProcurementPage page) {
      this.page = page;
    }

    @Override
    public ResponseEntity<ProcurementPage> searchProcurements(
        String q,
        String uf,
        String ibge,
        Integer modality,
        String minValue,
        String maxValue,
        Boolean open,
        ProcurementStatus status,
        String cursor,
        Integer limit,
        String ifNoneMatch) {
      return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<ProcurementDetail> getProcurement(String id, String ifNoneMatch) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResponseEntity<SituationHistory> getProcurementHistory(String id) {
      throw new UnsupportedOperationException();
    }
  }

  private static MockMvc mvc(ProcurementPage page) {
    return MockMvcBuilders.standaloneSetup(new FixedProcurements(page)).build();
  }

  private static ProcurementPage validPage() {
    return new ProcurementPage().items(List.of(summary())).limit(20);
  }

  @Test
  void validResponseConformsToTheContract() throws Exception {
    mvc(validPage())
        .perform(get("/api/v1/procurements").param("uf", "SP").param("limit", "20"))
        .andExpect(status().isOk())
        .andExpect(conforms());
  }

  @Test
  void responseMissingARequiredFieldIsRejected() {
    ProcurementPage withoutLimit = new ProcurementPage().items(List.of(summary()));

    assertThatThrownBy(
            () -> mvc(withoutLimit).perform(get("/api/v1/procurements")).andExpect(conforms()))
        .hasMessageContaining("limit");
  }

  @Test
  void requestOutsideTheContractIsRejected() {
    assertThatThrownBy(
            () ->
                mvc(validPage())
                    .perform(get("/api/v1/procurements").param("limit", "51"))
                    .andExpect(conforms()))
        .hasMessageContaining("limit");
  }

  @Test
  void answerOnlyIgnoresARequestOutsideTheContract() throws Exception {
    mvc(validPage())
        .perform(get("/api/v1/procurements").param("limit", "51"))
        .andExpect(answerConforms());
  }

  @Test
  void answerOnlyStillRejectsAResponseMissingARequiredField() {
    ProcurementPage withoutLimit = new ProcurementPage().items(List.of(summary()));

    assertThatThrownBy(
            () ->
                mvc(withoutLimit)
                    .perform(get("/api/v1/procurements").param("limit", "51"))
                    .andExpect(answerConforms()))
        .hasMessageContaining("limit");
  }
}
