package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.SearchSeed;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The search interface in a real browser: the whole application on a random port, the real Postgres
 * and rows seeded through the projector's own store. Only with {@code -Pe2e} ({@code make e2e-ui});
 * Playwright downloads the browser to {@code ~/.cache/ms-playwright} on the first run. {@code
 * PLAYWRIGHT_SKIP=1} skips it on a host that cannot run a browser.
 */
@Tag("e2e")
@DisabledIfEnvironmentVariable(named = "PLAYWRIGHT_SKIP", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"radar.outbox.relay-enabled=false", "radar.outbox.publisher=inmemory"})
class SearchUiE2EIT extends PostgresIT {

  private static final int ROWS = 45;
  private static final String RARE = id(5000);

  @LocalServerPort int port;
  @Autowired ProjectionStore store;

  private Playwright playwright;
  private Browser browser;

  @BeforeAll
  void seedAndOpenBrowser() {
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.procurement_stats_view, search.processed_event,"
                + " search.projection_checkpoint")
        .update();
    SearchSeed.load(store, SearchSeed.views(Instant.now()).subList(0, ROWS));
    store.project(
        discovered("rare", view(RARE, "Aquisição de telescópio astronômico", "100.00", 1), 1),
        ROWS);
    playwright = Playwright.create();
    browser = playwright.chromium().launch();
  }

  @AfterAll
  void closeBrowser() {
    browser.close();
    playwright.close();
  }

  @Test
  void typingFindsTheProcurementAndTheResultOpensItsDetail() {
    try (var page = browser.newPage()) {
      page.navigate("http://localhost:" + port + "/");

      var entries = page.evaluate("history.length");
      page.getByLabel("Texto da busca").pressSequentially("telescópio");

      assertThat(page.locator("#results-list > li")).hasCount(1);
      assertThat(page.locator("#results-list mark")).hasText("telescópio");
      assertThat(page).hasURL(Pattern.compile("/\\?q=telesc%C3%B3pio$"));
      org.assertj.core.api.Assertions.assertThat(page.evaluate("history.length"))
          .as("typing replaces the entry instead of piling up history")
          .isEqualTo(entries);

      page.locator("#results-list a").first().click();

      assertThat(page).hasTitle("Aquisição de telescópio astronômico | Radar de Licitações");
      assertThat(page.locator("h1")).hasText("Aquisição de telescópio astronômico");
    }
  }

  @Test
  void theMoreButtonAppendsTheNextPageAndTheLastOneRemovesIt() {
    try (var page = browser.newPage()) {
      page.navigate("http://localhost:" + port + "/");
      var items = page.locator("#results-list > li");
      assertThat(items).hasCount(20);

      page.getByRole(AriaRole.LINK, linkNamed("Carregar mais")).click();
      assertThat(items).hasCount(40);

      page.getByRole(AriaRole.LINK, linkNamed("Carregar mais")).click();
      assertThat(items).hasCount(ROWS + 1);
      assertThat(page.locator("a.more")).hasCount(0);
      assertThat(page.locator("#more-button")).hasText("Fim dos resultados.");
      assertThat(page).hasURL("http://localhost:" + port + "/");
    }
  }

  private static Page.GetByRoleOptions linkNamed(String name) {
    return new Page.GetByRoleOptions().setName(name);
  }
}
