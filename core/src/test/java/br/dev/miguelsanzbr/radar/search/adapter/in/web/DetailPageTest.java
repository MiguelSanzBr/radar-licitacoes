package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.PATH_ID;
import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.STORED_ID;
import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.detail;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.ProcurementNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DetailPage.class)
@Import({SecurityConfig.class, WebFixtures.FixedClock.class})
class DetailPageTest {

  @Autowired MockMvc mvc;
  @MockitoBean DetailUseCase detail;

  @Test
  void theDetailShowsTheFieldsAndTheLinkToTheOriginalNotice() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(detail(STORED_ID, "Aquisição de computadores"));

    mvc.perform(get("/licitacoes/" + PATH_ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<html lang=\"pt-BR\"")))
        .andExpect(
            content()
                .string(containsString("<title>Aquisição de computadores | Radar de Licitações")))
        .andExpect(content().string(containsString("<h1>Aquisição de computadores</h1>")))
        .andExpect(content().string(containsString("Prefeitura de Teste")))
        .andExpect(content().string(containsString("12345678000190")))
        .andExpect(content().string(containsString("Secretaria de Saúde")))
        .andExpect(content().string(containsString("São Paulo/SP")))
        .andExpect(content().string(containsString("Pregão - Eletrônico")))
        .andExpect(content().string(containsString("Aberto")))
        .andExpect(content().string(containsString("Lei 14.133/2021")))
        .andExpect(content().string(containsString("Suspensa")))
        .andExpect(content().string(containsString("Propostas em breve")))
        .andExpect(content().string(containsString("R$ 1.500,00")))
        .andExpect(content().string(containsString("20/10/2026 09:01")))
        .andExpect(content().string(containsString("href=\"/\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"https://pncp.gov.br/app/editais/12345678000190/2026/123\"")))
        .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")));
    verify(detail).find(STORED_ID);
  }

  @Test
  void theHistoryListsEveryChangeAndFlagsTheUnexpectedOnes() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(detail(STORED_ID, "Objeto"));

    mvc.perform(get("/licitacoes/" + PATH_ID))
        .andExpect(content().string(containsString("Histórico de situação")))
        .andExpect(content().string(containsString("Primeira observação")))
        .andExpect(content().string(containsString("Divulgada no PNCP")))
        .andExpect(content().string(containsString("20/10/2026 07:00")))
        .andExpect(content().string(containsString("20/10/2026 08:59")))
        .andExpect(content().string(containsString("Mudança inesperada")));
  }

  @Test
  void aProcurementWithoutHistoryOrOptionalFieldsStillRenders() throws Exception {
    var bare = detail(STORED_ID, "Objeto");
    when(detail.find(STORED_ID))
        .thenReturn(
            new br.dev.miguelsanzbr.radar.search.application.ProcurementDetail(
                bare.id(),
                bare.objectDescription(),
                bare.organCnpj(),
                bare.organName(),
                null,
                null,
                null,
                null,
                null,
                99,
                null,
                null,
                null,
                false,
                "PUBLISHED",
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.List.of(),
                "x"));

    mvc.perform(get("/licitacoes/" + PATH_ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Modalidade 99")))
        .andExpect(content().string(containsString("Prazo não informado")))
        .andExpect(content().string(containsString("não informado")))
        .andExpect(content().string(containsString("Ainda sem mudanças de situação")))
        .andExpect(content().string(not(containsString("Mudança inesperada"))));
  }

  @Test
  void anIdThatIsNotAControlNumberHasNoLinkToThePortal() throws Exception {
    when(detail.find("abc/2026")).thenReturn(detail("abc/2026", "Objeto"));

    mvc.perform(get("/licitacoes/abc-2026"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("pncp.gov.br"))));
  }

  @Test
  void textFromThePortalIsEscaped() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(detail(STORED_ID, "<script>alert(1)</script>"));

    mvc.perform(get("/licitacoes/" + PATH_ID))
        .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
        .andExpect(content().string(not(containsString("<script>alert"))));
  }

  @Test
  void anUnknownIdIsANotFoundPage() throws Exception {
    when(detail.find(STORED_ID)).thenThrow(new ProcurementNotFoundException(STORED_ID));

    mvc.perform(get("/licitacoes/" + PATH_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().string(containsString("<html lang=\"pt-BR\"")))
        .andExpect(content().string(containsString("Licitação não encontrada")))
        .andExpect(content().string(containsString("Voltar à busca")));
  }
}
