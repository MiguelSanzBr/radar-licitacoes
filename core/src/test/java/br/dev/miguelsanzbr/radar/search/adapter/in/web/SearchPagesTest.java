package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.NOW;
import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.PATH_ID;
import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.STORED_ID;
import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.hit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.SearchPage;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SearchPages.class)
@Import({SecurityConfig.class, WebFixtures.FixedClock.class})
class SearchPagesTest {

  private static final Cursor NEXT = new Cursor.ByRank(0.25, STORED_ID);

  @Autowired MockMvc mvc;
  @MockitoBean SearchUseCase search;

  private SearchQuery searched() {
    var captor = ArgumentCaptor.forClass(SearchQuery.class);
    verify(search).search(captor.capture());
    return captor.getValue();
  }

  private void answer(SearchPage page) {
    when(search.search(any())).thenReturn(page);
  }

  @Test
  void theFullPageHasTheFormAndTheResultsAreaWiredToHtmx() throws Exception {
    answer(new SearchPage(List.of(hit("Aquisição de papel", null)), null));

    var body =
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("<html lang=\"pt-BR\"")))
            .andExpect(content().string(containsString("<label for=\"q\"")))
            .andExpect(content().string(containsString("name=\"q\"")))
            .andExpect(content().string(containsString("<label for=\"uf\"")))
            .andExpect(content().string(containsString("<label for=\"modality\"")))
            .andExpect(content().string(containsString("<label for=\"open\"")))
            .andExpect(content().string(containsString("name=\"open\"")))
            .andExpect(content().string(containsString("hx-get=\"/fragments/results\"")))
            .andExpect(
                content()
                    .string(
                        containsString(
                            "hx-trigger=\"submit, change from:#uf, change from:#modality,"
                                + " change from:#open\"")))
            .andExpect(content().string(containsString("hx-trigger=\"keyup changed delay:400ms\"")))
            .andExpect(content().string(containsString("hx-include=\"#search-form\"")))
            .andExpect(content().string(containsString("hx-push-url=\"true\"")))
            .andExpect(content().string(containsString("hx-target=\"#results\"")))
            .andExpect(content().string(containsString("<section id=\"results\"")))
            .andExpect(content().string(containsString("Aquisição de papel")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("src=\"/js/htmx-2.0.11.min.js\"").contains("href=\"/css/app-");
    assertThat(body.split("<option value=\"[A-Z]{2}\"", -1)).hasSize(Uf.values().length + 1);
  }

  @Test
  void theFormKeepsWhatWasAskedFor() throws Exception {
    answer(new SearchPage(List.of(), null));

    mvc.perform(
            get("/")
                .param("q", "papel")
                .param("uf", "SP")
                .param("modality", "6")
                .param("open", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"papel\"")))
        .andExpect(content().string(containsString("<option value=\"SP\" selected=\"selected\"")))
        .andExpect(content().string(containsString("<option value=\"6\" selected=\"selected\"")))
        .andExpect(
            content()
                .string(containsString("type=\"checkbox\" value=\"true\" checked=\"checked\"")));

    var query = searched();
    assertThat(query.text()).isEqualTo("papel");
    assertThat(query.filters().uf()).isEqualTo(Uf.SP);
    assertThat(query.filters().modalityId()).isEqualTo(6);
    assertThat(query.filters().openOnly()).isTrue();
    assertThat(query.cursor()).isNull();
  }

  @Test
  void anEmptyFormSearchesWithoutFilters() throws Exception {
    answer(new SearchPage(List.of(), null));

    mvc.perform(get("/").param("q", "").param("uf", "").param("modality", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Nenhuma licitação encontrada")));

    var query = searched();
    assertThat(query.hasText()).isFalse();
    assertThat(query.filters().uf()).isNull();
    assertThat(query.filters().modalityId()).isNull();
    assertThat(query.filters().openOnly()).isFalse();
  }

  @Test
  void theFragmentIsOnlyTheListAndTellsHtmxTheAddressOfTheFullPage() throws Exception {
    answer(new SearchPage(List.of(hit("Aquisição de papel", null)), null));

    mvc.perform(
            get("/fragments/results")
                .header("HX-Request", "true")
                .param("q", "papel")
                .param("uf", "SP"))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Push-Url", "/?q=papel&uf=SP"))
        .andExpect(content().string(not(containsString("<html"))))
        .andExpect(content().string(not(containsString("<form"))))
        .andExpect(content().string(containsString("<ul id=\"results-list\"")))
        .andExpect(content().string(containsString("href=\"/licitacoes/" + PATH_ID + "\"")))
        .andExpect(content().string(containsString("Aquisição de papel")))
        .andExpect(content().string(containsString("Prefeitura de Teste")))
        .andExpect(content().string(containsString("São Paulo/SP")))
        .andExpect(content().string(containsString("Pregão - Eletrônico")))
        .andExpect(content().string(containsString("Propostas abertas")))
        .andExpect(content().string(containsString("R$ 1.500,00")))
        .andExpect(content().string(containsString("20/10/2026 10:00")))
        .andExpect(content().string(not(containsString("Carregar mais"))));
  }

  @Test
  void theAddressOfTheFullPageIsEncodedAndSkipsWhatIsEmpty() throws Exception {
    answer(new SearchPage(List.of(), null));

    mvc.perform(get("/fragments/results").param("q", "a+b&c ç").param("open", "true"))
        .andExpect(header().string("HX-Push-Url", "/?q=a%2Bb%26c%20%C3%A7&open=true"));
    mvc.perform(get("/fragments/results")).andExpect(header().string("HX-Push-Url", "/"));
    mvc.perform(get("/fragments/results").param("q", " ").param("uf", "").param("modality", "6"))
        .andExpect(header().string("HX-Push-Url", "/?modality=6"));
  }

  @Test
  void theMoreButtonCarriesTheNextCursorAndTheSameFilters() throws Exception {
    answer(new SearchPage(List.of(hit("Aquisição de papel", null)), NEXT));

    mvc.perform(get("/fragments/results").param("q", "papel").param("uf", "SP"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "hx-get=\"/fragments/results?q=papel&amp;uf=SP&amp;cursor="
                            + NEXT.encode()
                            + "\"")))
        .andExpect(content().string(containsString("hx-target=\"#results-list\"")))
        .andExpect(content().string(containsString("hx-swap=\"beforeend\"")))
        .andExpect(content().string(containsString("hx-push-url=\"false\"")))
        .andExpect(content().string(containsString("<a id=\"more-button\"")))
        .andExpect(content().string(containsString("Carregar mais")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"/?q=papel&amp;uf=SP&amp;cursor=" + NEXT.encode() + "\"")));
  }

  @Test
  void theMoreLinkWorksWithoutScriptsBecauseThePageTakesTheCursorToo() throws Exception {
    answer(new SearchPage(List.of(hit("Segunda página", null)), null));

    mvc.perform(get("/").param("q", "papel").param("cursor", NEXT.encode()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Segunda página")))
        .andExpect(content().string(containsString("<form")));
    assertThat(searched().cursor()).isEqualTo(NEXT);
  }

  @Test
  void aKeyUpSearchReplacesTheAddressAndASubmitPushesIt() throws Exception {
    answer(new SearchPage(List.of(), null));

    mvc.perform(get("/fragments/results").header("HX-Trigger", "q").param("q", "pap"))
        .andExpect(header().string("HX-Replace-Url", "/?q=pap"))
        .andExpect(header().doesNotExist("HX-Push-Url"));
    mvc.perform(get("/fragments/results").header("HX-Trigger", "search-form").param("q", "pap"))
        .andExpect(header().string("HX-Push-Url", "/?q=pap"))
        .andExpect(header().doesNotExist("HX-Replace-Url"));
  }

  @Test
  void aCursorRequestAnswersOnlyTheNextItemsAndTheNextButton() throws Exception {
    var after = new Cursor.ByRank(0.1, "99999999000199-1-000001/2026");
    answer(new SearchPage(List.of(hit("Mais papel", null)), after));

    mvc.perform(get("/fragments/results").param("q", "papel").param("cursor", NEXT.encode()))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("HX-Push-Url"))
        .andExpect(content().string(not(containsString("<ul id=\"results-list\""))))
        .andExpect(content().string(containsString("<li")))
        .andExpect(content().string(containsString("Mais papel")))
        .andExpect(content().string(containsString("<div id=\"more\" hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("cursor=" + after.encode())));
    assertThat(searched().cursor()).isEqualTo(NEXT);
  }

  @Test
  void theLastPageKeepsTheFocusTargetAndSaysItIsTheEnd() throws Exception {
    answer(new SearchPage(List.of(hit("Mais papel", null)), null));

    mvc.perform(get("/fragments/results").param("q", "papel").param("cursor", NEXT.encode()))
        .andExpect(content().string(containsString("<div id=\"more\" hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("id=\"more-button\" tabindex=\"-1\"")))
        .andExpect(content().string(containsString("Fim dos resultados")))
        .andExpect(content().string(not(containsString("Carregar mais"))));
  }

  @Test
  void theSnippetIsEscapedAndOnlyTheMarkedTermsBecomeMark() throws Exception {
    answer(
        new SearchPage(
            List.of(
                hit(
                    "<script>alert(1)</script>",
                    "compra de <b>papel</b> <script>alert(2)</script> <img src=x onerror=alert(3)>")),
            null));

    mvc.perform(get("/").param("q", "papel"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<mark>papel</mark>")))
        .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
        .andExpect(content().string(containsString("&lt;script&gt;alert(2)&lt;/script&gt;")))
        .andExpect(content().string(containsString("&lt;img src=x onerror=alert(3)&gt;")))
        .andExpect(content().string(not(containsString("<script>alert"))))
        .andExpect(content().string(not(containsString("<img"))));
  }

  @Test
  void aQueryEchoedBackIsEscapedToo() throws Exception {
    answer(new SearchPage(List.of(), null));

    mvc.perform(get("/").param("q", "\"><script>alert(1)</script>"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("<script>alert"))))
        .andExpect(content().string(containsString("&quot;&gt;&lt;script&gt;")));
  }

  @Test
  void aPhaseWithoutDatesIsShownAsUnknown() throws Exception {
    var open = hit("Sem datas", null);
    answer(
        new SearchPage(
            List.of(
                new br.dev.miguelsanzbr.radar.search.domain.RankedResult(
                    open.id(),
                    open.objectDescription(),
                    null,
                    open.organName(),
                    null,
                    "SP",
                    "São Paulo",
                    "3550308",
                    99,
                    "ANNULLED",
                    null,
                    null,
                    null,
                    NOW,
                    null)),
            null));

    mvc.perform(get("/"))
        .andExpect(content().string(containsString("Prazo não informado")))
        .andExpect(content().string(containsString("Anulada")))
        .andExpect(content().string(containsString("Valor não informado")))
        .andExpect(content().string(containsString("Modalidade 99")));
  }
}
