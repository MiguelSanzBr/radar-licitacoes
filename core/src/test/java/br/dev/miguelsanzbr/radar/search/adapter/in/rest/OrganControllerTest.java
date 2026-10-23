package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.answerConforms;
import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.OrganMatch;
import br.dev.miguelsanzbr.radar.search.application.OrganSearchUseCase;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OrganController.class)
@Import({SearchProblemAdvice.class, FixedLocaleConfiguration.class, SecurityConfig.class})
class OrganControllerTest {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";

  @Autowired MockMvc mvc;
  @MockitoBean OrganSearchUseCase organs;

  @Test
  void aTypoFindsTheOrgansWithTheirSimilarity() throws Exception {
    when(organs.search("prefeitra", OrganSearchUseCase.DEFAULT_LIMIT))
        .thenReturn(
            List.of(
                new OrganMatch("Prefeitura Municipal de Campinas", 0.8f),
                new OrganMatch("Prefeitura de Sorocaba", 0.5f)));

    mvc.perform(get("/api/v1/organs?q=prefeitra"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("Prefeitura Municipal de Campinas"))
        .andExpect(jsonPath("$.items[0].similarity").value(0.8))
        .andExpect(jsonPath("$.items[1].similarity").value(0.5))
        .andExpect(conforms());
  }

  @Test
  void noMatchIsAnEmptyList() throws Exception {
    when(organs.search("zzzz", OrganSearchUseCase.DEFAULT_LIMIT)).thenReturn(List.of());

    mvc.perform(get("/api/v1/organs?q=zzzz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(conforms());
  }

  @Test
  void aTextShorterThanTheContractAllowsIsAValidationProblem() throws Exception {
    mvc.perform(get("/api/v1/organs?q=a"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("q"))
        .andExpect(answerConforms());

    verifyNoInteractions(organs);
  }

  @Test
  void aMissingTextIsAValidationProblem() throws Exception {
    mvc.perform(get("/api/v1/organs"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("q"))
        .andExpect(answerConforms());
  }

  @Test
  void aBlankTextIsAValidationProblem() throws Exception {
    mvc.perform(get("/api/v1/organs").param("q", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("q"))
        .andExpect(answerConforms());

    verifyNoInteractions(organs);
  }

  @Test
  void aControlCharacterInTheTextIsAValidationProblem() throws Exception {
    mvc.perform(get(URI.create("/api/v1/organs?q=pre%00feitura")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("q"))
        .andExpect(conforms());

    verifyNoInteractions(organs);
  }

  @Test
  void theValidationMessagesAreInPortugueseWhateverTheClientAccepts() throws Exception {
    mvc.perform(get("/api/v1/organs?q=a").header("Accept-Language", "en"))
        .andExpect(jsonPath("$.errors[0].message").value("tamanho deve ser entre 2 e 100"))
        .andExpect(answerConforms());
  }
}
