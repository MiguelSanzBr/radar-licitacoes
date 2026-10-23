package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.PATH_ID;
import static br.dev.miguelsanzbr.radar.search.adapter.in.web.WebFixtures.STORED_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.ProcurementNotFoundException;
import br.dev.miguelsanzbr.radar.search.application.SearchPage;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The pages must work under the policy of the security chain: scripts only from this origin, no
 * inline script or style, no handler attribute. These tests read the HTML the pages really emit.
 */
@WebMvcTest(controllers = {SearchPages.class, DetailPage.class})
@Import({SecurityConfig.class, WebFixtures.FixedClock.class})
class PageHygieneTest {

  private static final String HTMX_SHA256 =
      "d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717";
  private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\ssrc=)");
  private static final Pattern STYLE = Pattern.compile("<style|\\sstyle=");
  private static final Pattern HANDLER = Pattern.compile("\\s(on[a-z]+|hx-on[:\\w-]*)=");

  @Autowired MockMvc mvc;
  @MockitoBean SearchUseCase search;
  @MockitoBean DetailUseCase detail;

  @BeforeEach
  void stubReads() {
    when(search.search(any()))
        .thenReturn(new SearchPage(List.of(WebFixtures.hit("Objeto", "um <b>termo</b>")), null));
    when(detail.find(STORED_ID)).thenReturn(WebFixtures.detail(STORED_ID, "Objeto"));
    when(detail.find("inexistente/2026")).thenThrow(new ProcurementNotFoundException("x"));
  }

  private String body(String path, boolean htmx) throws Exception {
    var request = get(path);
    if (htmx) {
      request.header("HX-Request", "true");
    }
    return mvc.perform(request).andReturn().getResponse().getContentAsString();
  }

  @Test
  void everyPageCarriesThePolicyTheTemplatesAreWrittenFor() throws Exception {
    for (var path : List.of("/", "/fragments/results", "/licitacoes/" + PATH_ID)) {
      mvc.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(
              header()
                  .string(
                      "Content-Security-Policy",
                      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                          + " frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }
  }

  @Test
  void noPageHasInlineScriptStyleOrEventHandlers() throws Exception {
    var pages =
        List.of(
            body("/", false),
            body("/fragments/results", true),
            body("/licitacoes/" + PATH_ID, false),
            body("/licitacoes/inexistente-2026", false));

    for (var html : pages) {
      assertThat(INLINE_SCRIPT.matcher(html).find()).as("inline script").isFalse();
      assertThat(STYLE.matcher(html).find()).as("inline style").isFalse();
      assertThat(HANDLER.matcher(html).find()).as("event handler").isFalse();
    }
  }

  @Test
  void htmxIsConfiguredWithoutInjectedStylesNorEval() throws Exception {
    assertThat(body("/", false))
        .contains("name=\"htmx-config\"")
        .contains("\"includeIndicatorStyles\":false")
        .contains("\"allowEval\":false");
  }

  @Test
  void htmxAndTheStylesheetAreServedByTheApplicationItself() throws Exception {
    var script =
        mvc.perform(get("/js/htmx-2.0.11.min.js"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    mvc.perform(get("/css/app.css")).andExpect(status().isOk());

    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script)))
        .as("the file the layout comment records")
        .isEqualTo(HTMX_SHA256);
  }

  @Test
  void theStylesheetIsVersionedByContentAndCachedForAnHour() throws Exception {
    var link = Pattern.compile("href=\"(/css/app-[0-9a-f]{32}\\.css)\"").matcher(body("/", false));

    assertThat(link.find()).as("versioned stylesheet link").isTrue();
    mvc.perform(get(link.group(1)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=3600"));
    mvc.perform(get("/js/htmx-2.0.11.min.js"))
        .andExpect(header().string("Cache-Control", "max-age=3600"));
  }

  @Test
  void theLayoutNamesTheFileItLoadsAndItsChecksum() throws Exception {
    var layout =
        new String(
            getClass().getResourceAsStream("/templates/layout.html").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);

    assertThat(layout).contains("htmx 2.0.11").contains(HTMX_SHA256);
  }
}
