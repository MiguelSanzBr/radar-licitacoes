package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.eventId;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.application.Situation;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ScreenshotType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the screens of the README GIF ({@code make readme-gif}): the real PNCP day versioned in
 * {@code infra/compose/seed} is loaded through the projector's own store and a short flow is driven
 * in Chromium at 1280x800, one numbered PNG per step in {@code target/readme-capture}. Only with
 * {@code -Preadme-capture}.
 */
@Tag("readme-capture")
@DisabledIfEnvironmentVariable(named = "PLAYWRIGHT_SKIP", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"radar.outbox.relay-enabled=false", "radar.outbox.publisher=inmemory"})
class ReadmeCaptureIT extends PostgresIT {

  private static final Path SEED =
      Path.of("..", "infra", "compose", "seed", "pncp-2026-10-21-mod6.jsonl.gz");
  private static final Path OUT = Path.of("target", "readme-capture");
  private static final Pattern MEDICINES =
      Pattern.compile("medicamentos", Pattern.CASE_INSENSITIVE);
  private static final ZoneId PNCP_ZONE = ZoneId.of("America/Sao_Paulo");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired ProjectionStore store;

  private Playwright playwright;
  private Browser browser;
  private int frame;

  @BeforeAll
  void seedAndOpenBrowser() throws IOException {
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.procurement_stats_view, search.processed_event,"
                + " search.projection_checkpoint")
        .update();
    var offset = 0L;
    for (var view : realViews()) {
      store.project(
          new ProcurementEvent.Discovered(
              eventId("capture-" + view.id()), view.id(), 1, view.pncpUpdatedAt(), view),
          offset++);
    }
    Files.createDirectories(OUT);
    try (var old = Files.newDirectoryStream(OUT, "*.png")) {
      for (var file : old) {
        Files.delete(file);
      }
    }
    playwright = Playwright.create();
    browser = playwright.chromium().launch();
  }

  @AfterAll
  void closeBrowser() {
    browser.close();
    playwright.close();
  }

  @Test
  void recordsTheSearchAndDetailFlow() {
    try (var page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1280, 800))) {
      page.navigate("http://localhost:" + port + "/");
      assertThat(page.locator("#results-list > li")).hasCount(20);
      shot(page);

      // fill() fires no keyup, so the text sits in the box until the key below sends the search
      var query = page.getByLabel("Texto da busca");
      query.fill("medicamentos");
      shot(page);

      query.press("End");
      assertThat(page.locator("#results-list mark").first()).isVisible();
      shot(page);

      page.getByLabel("UF").selectOption("SP");
      assertThat(page).hasURL(Pattern.compile("uf=SP"));
      assertThat(page.locator("#results-list > li").first()).isVisible();
      shot(page);

      var items = page.locator("#results-list > li");
      var before = items.count();
      var more =
          page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Carregar mais"));
      more.scrollIntoViewIfNeeded();
      shot(page);

      more.click();
      assertThat(items).not().hasCount(before);
      items.nth(before).scrollIntoViewIfNeeded();
      shot(page);

      page.locator("#results-list a")
          .filter(new Locator.FilterOptions().setHasText(MEDICINES))
          .first()
          .click();
      assertThat(page.locator("h2")).hasText("Histórico de situação");
      shot(page);

      page.locator("h2").scrollIntoViewIfNeeded();
      shot(page);
    }
  }

  private void shot(Page page) {
    page.screenshot(
        new Page.ScreenshotOptions()
            .setPath(OUT.resolve("%02d.png".formatted(++frame)))
            .setType(ScreenshotType.PNG));
  }

  /** Every notice of the versioned day, in the shape the projector keeps. */
  private static List<ProcurementView> realViews() throws IOException {
    var views = new ArrayList<ProcurementView>();
    try (var in = new GZIPInputStream(Files.newInputStream(SEED))) {
      for (var line : new String(in.readAllBytes()).split("\n")) {
        for (var item : JSON.readTree(line).required("data")) {
          views.add(view(item));
        }
      }
    }
    return views;
  }

  private static ProcurementView view(JsonNode n) {
    var id = n.required("numeroControlePNCP").asString();
    var situation =
        new Situation(
            n.required("situacaoCompraId").asInt(), n.required("situacaoCompraNome").asString());
    var status =
        switch (situation.id()) {
          case 1 -> "PUBLISHED";
          case 2 -> "REVOKED";
          case 3 -> "ANNULLED";
          default -> "SUSPENDED";
        };
    var organ = n.required("orgaoEntidade");
    var unit = n.required("unidadeOrgao");
    var estimated = n.path("valorTotalEstimado");
    var opening = instant(n.path("dataAberturaProposta"));
    var closing = instant(n.path("dataEncerramentoProposta"));
    var published = instant(n.path("dataPublicacaoPncp"));
    var updated = instant(n.path("dataAtualizacaoGlobal"));
    var object = n.required("objetoCompra").asString().strip();

    var json = JSON.createObjectNode();
    json.put("id", id);
    json.put("status", status);
    json.putObject("situation").put("id", situation.id()).put("name", situation.name());
    json.putObject("organ")
        .put("cnpj", organ.required("cnpj").asString())
        .put("name", organ.required("razaoSocial").asString());
    json.putObject("unit")
        .put("code", unit.required("codigoUnidade").asString())
        .put("name", unit.required("nomeUnidade").asString())
        .put("uf", unit.required("ufSigla").asString())
        .put("municipalityName", unit.required("municipioNome").asString())
        .put("ibgeCode", unit.required("codigoIbge").asString());
    json.putObject("modality")
        .put("id", n.required("modalidadeId").asInt())
        .put("name", n.required("modalidadeNome").asString());
    json.putObject("disputeMode").put("name", n.required("modoDisputaNome").asString());
    json.putObject("legalBasis").put("name", n.required("amparoLegal").required("nome").asString());
    json.put("srp", n.required("srp").asBoolean());
    json.put(
        "estimatedValue", estimated.isNull() ? null : estimated.decimalValue().toPlainString());
    json.putNull("awardedValue");
    json.putObject("proposalWindow")
        .put("openingAt", opening == null ? null : opening.toString())
        .put("closingAt", closing == null ? null : closing.toString());
    json.put("publishedAt", published.toString());
    json.put("pncpUpdatedAt", updated.toString());
    json.put("objectDescription", object);
    json.put("revision", 1);

    return new ProcurementView(
        id,
        status,
        situation,
        organ.required("razaoSocial").asString(),
        unit.required("nomeUnidade").asString(),
        unit.required("ufSigla").asString(),
        unit.required("municipioNome").asString(),
        unit.required("codigoIbge").asString(),
        n.required("modalidadeId").asInt(),
        n.required("modalidadeNome").asString(),
        estimated.isNull() ? null : new BigDecimal(estimated.decimalValue().toPlainString()),
        opening,
        closing,
        published,
        updated,
        object,
        json.toString());
  }

  /** PNCP datetimes carry no offset and are local to Brasilia. */
  private static Instant instant(JsonNode text) {
    return text.isNull()
        ? null
        : LocalDateTime.parse(text.asString()).atZone(PNCP_ZONE).toInstant();
  }
}
