package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.search.SearchSeed;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hostile text in the query string of the public reads (OWASP A03). The text is bound as a value
 * everywhere, so each payload is just a search that finds nothing or something odd: 200, or a 400
 * problem where the contract refuses it, never a 500; and the read model is still there after.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InjectionAttackIT extends PostgresIT {

  private static final String VIEW = "search.procurement_search_view";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int SEEDED = 30;

  /** SQL breakers, LIKE and trigram wildcards, tsquery operators, odd Unicode. */
  private static final List<String> TEXTS =
      List.of(
          "' OR 1=1 --",
          "'); DROP TABLE search.procurement_search_view; --",
          "\" OR \"\"=\"",
          "'; SELECT pg_sleep(30); --",
          "\\",
          "\\'",
          "%",
          "_",
          "%%",
          "& | ! <->",
          "a & b | !c <-> d",
          "limpeza:* & (",
          "'",
          "\"",
          "((((",
          "/* x */ -- y",
          "ação ☃ 😀 ‮ ́",
          "ＤＲＯＰ ＴＡＢＬＥ",
          "x".repeat(200));

  /** What the contract refuses before any query: control characters and the length limits. */
  private static final List<String> REFUSED =
      List.of("a\u0000b", "a\nb", "a\tb", "x".repeat(201), "x".repeat(20_000));

  @Autowired MockMvc mvc;
  @Autowired ProjectionStore store;

  private long rowsBefore;

  @BeforeAll
  void seed() {
    truncate();
    SearchSeed.load(store, SearchSeed.views(Instant.now()).subList(0, SEEDED));
    rowsBefore = rows();
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

  private long rows() {
    return jdbc.sql("SELECT count(*) FROM " + VIEW).query(Long.class).single();
  }

  private void theReadModelIsIntact() {
    assertThat(jdbc.sql("SELECT to_regclass('" + VIEW + "')").query(String.class).single())
        .isEqualTo(VIEW);
    assertThat(rows()).isEqualTo(rowsBefore).isEqualTo(SEEDED);
  }

  static Stream<String> texts() {
    return TEXTS.stream();
  }

  static Stream<String> refused() {
    return REFUSED.stream();
  }

  @ParameterizedTest
  @MethodSource("texts")
  void procurementSearchTreatsTheTextAsTextOnly(String text) throws Exception {
    var response =
        mvc.perform(get("/api/v1/procurements").param("q", text).param("limit", "50"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    // a payload that escaped the value would return every row, or fail the statement
    assertThat(JSON.readTree(response.getContentAsString()).get("items").size()).isLessThan(SEEDED);
    theReadModelIsIntact();
  }

  @ParameterizedTest
  @MethodSource("refused")
  void procurementSearchRefusesWhatTheContractForbids(String text) throws Exception {
    var response =
        mvc.perform(get("/api/v1/procurements").param("q", text)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    theReadModelIsIntact();
  }

  @ParameterizedTest
  @MethodSource("texts")
  void organSearchTreatsTheTextAsTextOnly(String text) throws Exception {
    var response = mvc.perform(get("/api/v1/organs").param("q", text)).andReturn().getResponse();

    // the contract keeps 2 to 100 characters: below or above is a 400, otherwise a plain search
    var inRange = text.length() >= 2 && text.length() <= 100 && !text.isBlank();
    assertThat(response.getStatus()).isEqualTo(inRange ? 200 : 400);
    theReadModelIsIntact();
  }

  /** The page and the fragment htmx swaps in take the same text through the same use case. */
  @ParameterizedTest
  @MethodSource("pageTexts")
  void theSearchPagesTreatTheTextAsTextOnly(String path, String text) throws Exception {
    var response = mvc.perform(get(path).param("q", text)).andReturn().getResponse();

    assertThat(response.getStatus()).as(path).isEqualTo(REFUSED.contains(text) ? 400 : 200);
    assertThat(response.getContentAsString()).doesNotContain("Exception", "org.jooq", "at br.dev");
    theReadModelIsIntact();
  }

  static Stream<Arguments> pageTexts() {
    return Stream.of("/", "/fragments/results")
        .flatMap(
            path ->
                Stream.concat(TEXTS.stream(), REFUSED.stream())
                    .map(text -> Arguments.of(path, text)));
  }

  @Test
  void organSearchRefusesControlCharactersAndLongText() throws Exception {
    for (var text : REFUSED) {
      var response = mvc.perform(get("/api/v1/organs").param("q", text)).andReturn().getResponse();

      assertThat(response.getStatus()).as(text).isEqualTo(400);
    }
    theReadModelIsIntact();
  }

  /** Every other parameter takes the same hostile text: 4xx for a bad value, never a 500. */
  @ParameterizedTest
  @MethodSource("filters")
  void filtersNeverReachTheDatabaseAsText(String name, String text) throws Exception {
    var status =
        mvc.perform(get("/api/v1/procurements").param(name, text)).andReturn().getResponse();

    assertThat(status.getStatus()).as(name + "=" + text).isLessThan(500);
    theReadModelIsIntact();
  }

  static Stream<Arguments> filters() {
    return Stream.of("uf", "ibge", "modality", "minValue", "maxValue", "open", "status", "limit")
        .flatMap(name -> TEXTS.stream().limit(5).map(text -> Arguments.of(name, text)));
  }
}
