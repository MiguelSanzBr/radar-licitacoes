package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.SearchFixtures;
import br.dev.miguelsanzbr.radar.search.SearchSeed;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Markup in what the pages show (OWASP A03, script injection): the text a procuring organ wrote and
 * the text the visitor typed come back escaped, with the one exception of the {@code <b>} that the
 * database puts around a matched term.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HtmlEscapingIT extends PostgresIT {

  private static final String ID = id(1);
  private static final String HOSTILE =
      "limpeza <script>alert(1)</script> <img src=x onerror=alert(2)> <a href=\"javascript:x\">";
  private static final List<String> MARKUP =
      List.of("<script>alert", "<img src=x", "<a href=\"javascript", "onerror=alert(2)>");

  @Autowired MockMvc mvc;
  @Autowired ProjectionStore store;

  @BeforeAll
  void seed() {
    truncate();
    SearchSeed.load(store, List.of(SearchFixtures.view(ID, HOSTILE, "1000.00", 1)));
  }

  @AfterAll
  void clean() {
    truncate();
  }

  private void truncate() {
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.processed_event, search.projection_checkpoint,"
                + " search.procurement_stats_view")
        .update();
  }

  private String page(String path, String... query) throws Exception {
    var request = get(path);
    for (var i = 0; i < query.length; i += 2) {
      request.param(query[i], query[i + 1]);
    }
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(path).isEqualTo(200);
    return response.getContentAsString();
  }

  @Test
  void textFromTheProcuringOrganIsEscapedInTheListTheFragmentAndTheDetail() throws Exception {
    var pages =
        List.of(
            page("/", "q", "limpeza"),
            page("/fragments/results", "q", "limpeza"),
            page("/licitacoes/" + ProcurementIds.toPath(ID)));

    for (var html : pages) {
      assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain(MARKUP);
    }
  }

  @Test
  void theTextTheVisitorTypedComesBackEscaped() throws Exception {
    var typed = "\"><script>alert(1)</script><img src=x onerror=alert(2)>";

    var whole = page("/", "q", typed);
    var fragment = page("/fragments/results", "q", typed);

    for (var html : List.of(whole, fragment)) {
      assertThat(html).doesNotContain(MARKUP).doesNotContain("\"><script>");
    }
    assertThat(whole).contains("&quot;&gt;&lt;script&gt;");
  }

  @Test
  void theAddressHtmxPushesCarriesTheTextEncoded() throws Exception {
    var typed = "<script>alert(1)</script>\"'";

    var pushed =
        mvc.perform(get("/fragments/results").param("q", typed))
            .andReturn()
            .getResponse()
            .getHeader("HX-Push-Url");

    assertThat(pushed).startsWith("/?q=%3Cscript%3E").doesNotContain("<", ">", "\"", "'", " ");
  }
}
