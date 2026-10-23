package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.RedisTestConfig;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.SearchSeed;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The routes end to end: HTTP, security chain, rate limit, jOOQ and the real Postgres views. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "radar.rate-limit.enabled=true")
@Import(RedisTestConfig.class)
class ProcurementsRestIT extends PostgresIT {

  private static final int ROWS = 60;
  // the seed rows carry an empty snapshot; this one is complete, as the events are
  private static final String DETAILED = id(5000);
  private static final String LIST = "/api/v1/procurements";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired ProjectionStore store;

  @BeforeAll
  void seed() {
    truncate();
    SearchSeed.load(store, SearchSeed.views(Instant.now()).subList(0, ROWS));
    store.project(discovered("detailed", view(DETAILED, "Objeto completo", "100.00", 1), 1), ROWS);
  }

  @AfterAll
  void clean() {
    truncate();
  }

  private void truncate() {
    jdbc.sql(
            "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
                + " search.procurement_stats_view, search.processed_event,"
                + " search.projection_checkpoint")
        .update();
  }

  @Test
  void aTextSearchRanksTheBestHitFirstAndMarksTheTerms() throws Exception {
    mvc.perform(get(LIST).param("q", "ambulância"))
        .andExpect(status().isOk())
        .andExpect(header().exists("RateLimit-Limit"))
        .andExpect(header().string("ETag", startsWith("W/\"")))
        .andExpect(jsonPath("$.items[0].id").value(ProcurementIds.toPath(id(1))))
        .andExpect(jsonPath("$.items[0].snippet").value(containsString("<b>")))
        .andExpect(jsonPath("$.items[1].id").value(ProcurementIds.toPath(id(2))))
        .andExpect(conforms());
  }

  @Test
  void followingTheLinkHeaderVisitsEveryRowOnce() throws Exception {
    var seen = new ArrayList<String>();
    var target = LIST + "?limit=25";
    while (target != null) {
      var response =
          mvc.perform(get(URI.create(target))).andExpect(conforms()).andReturn().getResponse();
      var items = JSON.readTree(response.getContentAsString()).get("items");
      items.forEach(item -> seen.add(item.get("id").asString()));
      var link = response.getHeader("Link");
      target = link == null ? null : link.substring(1, link.indexOf('>'));
    }

    assertThat(seen).hasSize(ROWS + 1).doesNotHaveDuplicates();
  }

  @Test
  void theDetailRoundTripsItsEtagAndAnswersNotModified() throws Exception {
    var path = LIST + "/" + ProcurementIds.toPath(DETAILED);

    var etag =
        mvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(ProcurementIds.toPath(DETAILED)))
            .andExpect(jsonPath("$.history.length()").value(1))
            .andExpect(jsonPath("$.history[0].to").value("Divulgada no PNCP"))
            .andExpect(conforms())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    assertThat(etag).matches("\"[0-9a-f]{64}\"");
    mvc.perform(get(path).header("If-None-Match", etag))
        .andExpect(status().isNotModified())
        .andExpect(conforms());
  }

  @Test
  void theHistoryAndAnUnknownIdAreServedToo() throws Exception {
    mvc.perform(get(LIST + "/" + ProcurementIds.toPath(DETAILED) + "/history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(conforms());
    mvc.perform(get(LIST + "/" + ProcurementIds.toPath(id(999))))
        .andExpect(status().isNotFound())
        .andExpect(conforms());
  }

  @Test
  void aTamperedCursorIsAProblemEvenThroughTheRealStack() throws Exception {
    mvc.perform(get(LIST).param("cursor", "adulterado"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(endsWith("invalid-cursor.md")));
  }

  @Test
  void theStatsCountEveryProjectedRow() throws Exception {
    var body =
        mvc.perform(get("/api/v1/stats").param("groupBy", "uf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groupBy").value("uf"))
            .andExpect(conforms())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var total = 0L;
    for (var bucket : JSON.readTree(body).get("buckets")) {
      total += bucket.get("count").asLong();
    }
    assertThat(total).isEqualTo(ROWS + 1);
  }

  @Test
  void aTypoFindsTheOrgan() throws Exception {
    mvc.perform(get("/api/v1/organs").param("q", "prefeitra"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value(containsString("Prefeitura")))
        .andExpect(conforms());
  }
}
