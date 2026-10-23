package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Whatever goes wrong, the person sees a sentence in Portuguese and a way back, never a trace. */
@WebMvcTest(controllers = SearchPages.class)
@Import({SecurityConfig.class, WebFixtures.FixedClock.class})
class WebErrorsTest {

  @Autowired MockMvc mvc;
  @MockitoBean SearchUseCase search;

  @Test
  void aBadFilterIsAFullPageWithABackLink() throws Exception {
    mvc.perform(get("/").param("uf", "XX"))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist("HX-Push-Url"))
        .andExpect(content().string(containsString("<html lang=\"pt-BR\"")))
        .andExpect(content().string(containsString("UF inválida")))
        .andExpect(content().string(containsString("role=\"alert\"")))
        .andExpect(content().string(containsString("href=\"/\"")));
  }

  @Test
  void theSameErrorInsideHtmxIsOnlyTheMessageAimedAtTheResultsArea() throws Exception {
    mvc.perform(get("/fragments/results").header("HX-Request", "true").param("uf", "XX"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("HX-Retarget", "#results"))
        .andExpect(header().string("HX-Reswap", "innerHTML"))
        .andExpect(header().string("HX-Push-Url", "false"))
        .andExpect(content().string(not(containsString("<html"))))
        .andExpect(content().string(containsString("UF inválida")))
        .andExpect(content().string(containsString("role=\"alert\"")));
  }

  @Test
  void aModalityThatIsNotANumberIsRejected() throws Exception {
    mvc.perform(get("/").param("modality", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Filtro inválido")));
  }

  @Test
  void aForgedCursorIsRejected() throws Exception {
    mvc.perform(get("/fragments/results").param("q", "papel").param("cursor", "adulterado"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Refaça a busca")));
  }

  @Test
  void aCursorOfAnotherKindOfQueryIsRejected() throws Exception {
    mvc.perform(
            get("/fragments/results")
                .param("cursor", new Cursor.ByRank(0.25, WebFixtures.STORED_ID).encode()))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Refaça a busca")));
  }

  @Test
  void aTextOverTheLimitIsRejected() throws Exception {
    mvc.perform(get("/").param("q", "a".repeat(201)))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("no máximo 200 caracteres")));
  }

  @Test
  void controlCharactersAreRejected() throws Exception {
    mvc.perform(get("/").param("q", "pa\u0000pel"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("caracteres de controle")));
  }

  @Test
  void aSlowReadAsksToTryAgainLater() throws Exception {
    when(search.search(any())).thenThrow(new ReadTimeoutException());

    mvc.perform(get("/").param("q", "papel"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"))
        .andExpect(content().string(containsString("A consulta demorou demais")));
  }

  @Test
  void anUnexpectedFailureDoesNotLeakItsCause() throws Exception {
    when(search.search(any())).thenThrow(new IllegalStateException("segredo interno"));

    mvc.perform(get("/"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(containsString("Erro inesperado")))
        .andExpect(content().string(not(containsString("segredo interno"))));
  }

  @Test
  void anUnknownAddressGetsTheFriendlyPageWithoutDetails() throws Exception {
    mvc.perform(
            get("/error")
                .accept("text/html")
                .requestAttr("jakarta.servlet.error.status_code", 404)
                .requestAttr("jakarta.servlet.error.request_uri", "/segredo/interno")
                .requestAttr("jakarta.servlet.error.message", "No static resource segredo"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("<html lang=\"pt-BR\"")))
        .andExpect(content().string(containsString("Página não encontrada")))
        .andExpect(content().string(containsString("href=\"/\"")))
        .andExpect(content().string(not(containsString("segredo"))));
  }

  @Test
  void anyOtherContainerErrorIsTheGenericPage() throws Exception {
    mvc.perform(
            get("/error")
                .accept("text/html")
                .requestAttr("jakarta.servlet.error.status_code", 500)
                .requestAttr("jakarta.servlet.error.message", "falha com detalhe"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string(containsString("Erro inesperado")))
        .andExpect(content().string(not(containsString("detalhe"))));
  }
}
