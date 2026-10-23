package br.dev.miguelsanzbr.radar.search.adapter.in.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.SearchSeed;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The bench-only OFFSET page answers the same rows as the keyset list, so the two can be timed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "radar.bench.offset-endpoint=true")
class BenchOffsetEndpointIT extends PostgresIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LIST = "/api/v1/procurements?limit=10";

  @Autowired MockMvc mvc;
  @Autowired ProjectionStore store;

  @BeforeAll
  void seed() {
    truncate();
    SearchSeed.load(store, SearchSeed.views(Instant.now()).subList(0, 60));
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

  private JsonNode json(String path) throws Exception {
    return JSON.readTree(
        mvc.perform(get(path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static List<String> ids(JsonNode page) {
    return page.get("items").valueStream().map(item -> item.get("id").asString()).toList();
  }

  @Test
  void anOffsetPageHoldsTheRowsOfTheEquivalentKeysetPage() throws Exception {
    var first = json(LIST);
    var second = json(LIST + "&cursor=" + first.get("nextCursor").asString());

    assertThat(ids(json("/bench/offset?page=2&limit=10"))).isEqualTo(ids(second));
  }

  @Test
  void theNextCursorLeadsToTheFollowingKeysetPage() throws Exception {
    var cursor = json("/bench/offset?page=2&limit=10").get("nextCursor").asString();

    assertThat(ids(json(LIST + "&cursor=" + cursor)))
        .isEqualTo(ids(json("/bench/offset?page=3&limit=10")));
  }

  @Test
  void pageAndLimitStayInsideTheirBounds() throws Exception {
    assertThat(json("/bench/offset?page=0&limit=1000").get("items")).hasSize(50);
    assertThat(json("/bench/offset?page=-3&limit=0").get("items")).hasSize(1);
  }

  @Test
  void pastTheLastRowThereIsNoNextCursor() throws Exception {
    var tail = json("/bench/offset?page=7&limit=10");

    assertThat(tail.get("items")).isEmpty();
    assertThat(tail.has("nextCursor")).isFalse();
  }
}
