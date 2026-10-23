package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.answerConforms;
import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.GroupBy;
import br.dev.miguelsanzbr.radar.search.application.StatsRow;
import br.dev.miguelsanzbr.radar.search.application.StatsUseCase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = StatsController.class)
@Import({SearchProblemAdvice.class, SecurityConfig.class})
class StatsControllerTest {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";

  @Autowired MockMvc mvc;
  @MockitoBean StatsUseCase stats;

  @Test
  void eachGroupIsABucketWithItsCountAndSum() throws Exception {
    when(stats.stats(GroupBy.UF))
        .thenReturn(
            List.of(
                new StatsRow("SP", 3, new BigDecimal("1500.50")),
                new StatsRow("XX", 1, new BigDecimal("0.00"))));

    mvc.perform(get("/api/v1/stats?groupBy=uf"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.groupBy").value("uf"))
        .andExpect(jsonPath("$.buckets.length()").value(2))
        .andExpect(jsonPath("$.buckets[0].key").value("SP"))
        .andExpect(jsonPath("$.buckets[0].count").value(3))
        .andExpect(jsonPath("$.buckets[0].estimatedSum").value("1500.50"))
        .andExpect(jsonPath("$.buckets[1].key").value("XX"))
        .andExpect(conforms());
  }

  @ParameterizedTest
  @CsvSource({"uf,UF", "modality,MODALITY", "month,MONTH", "valueBand,VALUE_BAND"})
  void theQueryValueSelectsTheGrouping(String value, GroupBy expected) throws Exception {
    when(stats.stats(expected)).thenReturn(List.of());

    mvc.perform(get("/api/v1/stats?groupBy=" + value))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groupBy").value(value))
        .andExpect(jsonPath("$.buckets.length()").value(0))
        .andExpect(conforms());

    verify(stats).stats(expected);
  }

  @Test
  void aDimensionThatDoesNotExistIsAValidationProblem() throws Exception {
    mvc.perform(get("/api/v1/stats?groupBy=bogus"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("groupBy"))
        .andExpect(answerConforms());

    verifyNoInteractions(stats);
  }

  @Test
  void aMissingDimensionIsAValidationProblem() throws Exception {
    mvc.perform(get("/api/v1/stats"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("groupBy"))
        .andExpect(answerConforms());
  }
}
