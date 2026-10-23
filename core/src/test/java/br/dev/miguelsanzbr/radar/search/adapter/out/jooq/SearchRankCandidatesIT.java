package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ranking of a text search is bounded: only the most recent {@code rank-candidates} matches are
 * ranked, however many rows the term matches (benchmark 2.7, S1). 100 thousand rows, a third of
 * them with the common term, stand for the million of the benchmark: the planner reaches the same
 * plan, and the plan, not the clock, is what is asserted.
 */
@OutboxSpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchRankCandidatesIT {

  private static final int ROWS = 100_000;
  private static final int CANDIDATES = 200;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired JdbcTemplate jdbc;
  @Autowired JooqSearchReadModel byDefault;

  @Autowired
  @Qualifier("searchReadDsl")
  DSLContext dsl;

  private JooqSearchReadModel bounded;

  @BeforeAll
  void load() {
    truncate();
    bounded = new JooqSearchReadModel(dsl, CANDIDATES);
    // ids grow as the rows get older; the rare term is on 20 rows spread over the whole range
    jdbc.execute(
        """
        INSERT INTO search.procurement_search_view
          (numero_controle_pncp, search_vector, object_description, organ_name, uf, modality_id,
           status, value_band, published_at, pncp_updated_at, source_version, projected_at)
        SELECT 'R' || lpad(g::text, 7, '0'), to_tsvector('search.portuguese_unaccent', t.text),
               t.text, 'Prefeitura de Teste', CASE WHEN g % 2 = 0 THEN 'SP' ELSE 'MG' END, 6,
               'PUBLISHED', 0, now() - g * interval '1 minute', now(), 1, now()
        FROM generate_series(1, {rows}) AS g
        CROSS JOIN LATERAL (
          SELECT CASE WHEN g % 5000 = 1 THEN 'metalurgia'
                      WHEN g % 3 = 0 THEN 'contratação de empresa' || repeat(' contratação', g % 4)
                      ELSE 'aquisição de material' END AS text) AS t
        """
            .replace("{rows}", Integer.toString(ROWS)));
    jdbc.execute("ANALYZE search.procurement_search_view");
  }

  @AfterAll
  void clean() {
    truncate();
  }

  private void truncate() {
    jdbc.execute("TRUNCATE search.procurement_search_view");
  }

  private static SearchQuery query(String text, Filters filters, Cursor cursor, int limit) {
    return new SearchQuery(text, filters, cursor, limit);
  }

  private List<RankedResult> all(JooqSearchReadModel model, String text, Filters filters) {
    var items = new ArrayList<RankedResult>();
    Cursor cursor = null;
    do {
      var page = model.search(query(text, filters, cursor, 20));
      items.addAll(page.items());
      cursor = page.nextCursor();
    } while (cursor != null);
    return items;
  }

  /** The most rows any node of the plan, run for real, hands to its parent. */
  private long mostRowsOfAnyNode(JooqSearchReadModel model, SearchQuery query) {
    var sql = model.query(query).orElseThrow().getSQL(ParamType.INLINED);
    var json = jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + sql, String.class);
    return mostRows(JSON.readTree(json).get(0).get("Plan"));
  }

  private static long mostRows(JsonNode node) {
    var most = node.get("Actual Rows").asLong() * node.get("Actual Loops").asLong();
    for (var child : node.path("Plans")) {
      most = Math.max(most, mostRows(child));
    }
    return most;
  }

  @Test
  void aCommonTermRanksNoMoreThanTheCandidates() {
    var common = query("contratacao", Filters.NONE, null, 20);

    assertThat(mostRowsOfAnyNode(bounded, common)).isLessThanOrEqualTo(CANDIDATES);
    // the control: without the bound the same statement ranks every match (a third of the table)
    var unbounded = new JooqSearchReadModel(dsl, Integer.MAX_VALUE);
    assertThat(mostRowsOfAnyNode(unbounded, common)).isGreaterThan(ROWS / 4);
  }

  @Test
  void theCandidatesAreTheMostRecentMatchesAndOnlyTheyAreRanked() {
    var found = all(bounded, "contratacao", Filters.NONE);

    assertThat(found).hasSize(CANDIDATES);
    var ids = found.stream().map(RankedResult::id).toList();
    assertThat(ids).doesNotHaveDuplicates();
    // matches are every third row from the newest; the 200 newest are g = 3, 6, ... 600
    assertThat(ids)
        .allSatisfy(id -> assertThat(Integer.parseInt(id.substring(1))).isLessThanOrEqualTo(600));
    for (var i = 1; i < found.size(); i++) {
      var before = found.get(i - 1);
      var after = found.get(i);
      assertThat(
              before.rank() > after.rank()
                  || (before.rank().equals(after.rank()) && before.id().compareTo(after.id()) > 0))
          .as("%s then %s", before, after)
          .isTrue();
    }
  }

  @Test
  void aNewerMatchInsertedBetweenPagesNeitherRepeatsNorSkipsWhatThePagesSaw() {
    var before = all(bounded, "contratacao", Filters.NONE).stream().map(RankedResult::id).toList();
    var seen = new ArrayList<String>();
    var first = bounded.search(query("contratacao", Filters.NONE, null, 20));
    first.items().forEach(r -> seen.add(r.id()));
    jdbc.execute(
        """
        INSERT INTO search.procurement_search_view
          (numero_controle_pncp, search_vector, object_description, organ_name, uf, modality_id,
           status, value_band, published_at, pncp_updated_at, source_version, projected_at)
        VALUES ('NEWER', to_tsvector('search.portuguese_unaccent', 'contratação'), 'contratação',
                'Prefeitura de Teste', 'SP', 6, 'PUBLISHED', 0, now() + interval '1 hour', now(), 1,
                now())
        """);
    try {
      var cursor = first.nextCursor();
      while (cursor != null) {
        var page = bounded.search(query("contratacao", Filters.NONE, cursor, 20));
        page.items().forEach(r -> seen.add(r.id()));
        cursor = page.nextCursor();
      }
    } finally {
      jdbc.execute(
          "DELETE FROM search.procurement_search_view WHERE numero_controle_pncp = 'NEWER'");
    }

    assertThat(seen).doesNotHaveDuplicates();
    // the new row pushes the oldest candidate (the largest id: ids grow with age) out of the set;
    // every other one is still reached
    var oldest = before.stream().max(String::compareTo).orElseThrow();
    assertThat(seen).containsAll(before.stream().filter(id -> !id.equals(oldest)).toList());
  }

  @Test
  void aTermWithFewerMatchesThanTheCandidatesIsNotCut() {
    var found = all(bounded, "metalurgia", Filters.NONE);

    assertThat(found).hasSize(20);
    assertThat(all(new JooqSearchReadModel(dsl, Integer.MAX_VALUE), "metalurgia", Filters.NONE))
        .extracting(RankedResult::id)
        .containsExactlyElementsOf(found.stream().map(RankedResult::id).toList());
  }

  @Test
  void theFiltersNarrowTheCandidatesBeforeTheyAreCounted() {
    var sp = new Filters(Uf.SP, null, null, null, null, null, null);

    var found = all(bounded, "contratacao", sp);

    assertThat(found).hasSize(CANDIDATES).allSatisfy(r -> assertThat(r.uf()).isEqualTo("SP"));
  }

  @Test
  void theDefaultKeepsTwoThousandCandidates() {
    var sql =
        byDefault
            .query(query("contratacao", Filters.NONE, null, 20))
            .orElseThrow()
            .getSQL(ParamType.INLINED);

    assertThat(sql).containsIgnoringCase("limit 2000");
  }

  @Test
  void theCandidatesMustBeAtLeastOne() {
    assertThatThrownBy(() -> new JooqSearchReadModel(dsl, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
