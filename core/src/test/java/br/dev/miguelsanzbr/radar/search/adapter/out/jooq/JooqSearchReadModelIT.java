package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchSeed.RELEVANT_BY_OBJECT;
import static br.dev.miguelsanzbr.radar.search.SearchSeed.RELEVANT_BY_ORGAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.SearchSeed;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.InvalidCursorException;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@OutboxSpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JooqSearchReadModelIT {

  private static final Comparator<ProcurementView> NEWEST_FIRST =
      Comparator.comparing(ProcurementView::publishedAt)
          .thenComparing(ProcurementView::id)
          .reversed();

  @Autowired ProjectionStore store;
  @Autowired SearchUseCase search;
  @Autowired JooqSearchReadModel readModel;
  @Autowired JdbcTemplate jdbc;

  private Instant now;
  private List<ProcurementView> seed;

  @BeforeAll
  void seed() {
    truncate();
    now = Instant.now();
    seed = SearchSeed.views(now);
    SearchSeed.load(store, seed);
    jdbc.execute("ANALYZE search.procurement_search_view");
  }

  @AfterAll
  void clean() {
    truncate();
  }

  private void truncate() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  private static Filters only(Uf uf) {
    return new Filters(uf, null, null, null, null, null, null);
  }

  /** Every page of the query, followed through the cursor. */
  private List<RankedResult> all(String text, Filters filters, int limit) {
    var items = new ArrayList<RankedResult>();
    Cursor cursor = null;
    do {
      var page = search.search(new SearchQuery(text, filters, cursor, limit));
      assertThat(page.items()).hasSizeLessThanOrEqualTo(limit);
      items.addAll(page.items());
      cursor = page.nextCursor();
    } while (cursor != null);
    return items;
  }

  private List<String> ids(List<RankedResult> results) {
    return results.stream().map(RankedResult::id).toList();
  }

  private List<String> expected(Predicate<ProcurementView> predicate) {
    return seed.stream().filter(predicate).sorted(NEWEST_FIRST).map(ProcurementView::id).toList();
  }

  private List<String> idsOf(Filters filters) {
    return ids(all("", filters, 50));
  }

  private boolean open(ProcurementView v) {
    return v.status().equals("PUBLISHED")
        && v.proposalClosingAt() != null
        && v.proposalClosingAt().isAfter(now)
        && (v.proposalOpeningAt() == null || !v.proposalOpeningAt().isAfter(now));
  }

  private static boolean mentions(ProcurementView v, String word) {
    return v.objectDescription().toLowerCase().contains(word);
  }

  private static boolean valued(ProcurementView v, String min, String max) {
    var value = v.estimatedValue();
    return value != null
        && value.compareTo(new BigDecimal(min)) >= 0
        && value.compareTo(new BigDecimal(max)) <= 0;
  }

  @Test
  void aMatchInTheObjectRanksAboveAMatchOnlyInTheOrganName() {
    var found = all("ambulancia", Filters.NONE, 20);

    assertThat(ids(found)).containsExactly(RELEVANT_BY_OBJECT, RELEVANT_BY_ORGAN);
    assertThat(found.get(0).rank()).isGreaterThan(found.get(1).rank());
  }

  @Test
  void accentsAndInflectionAreIgnored() {
    var byLicitacao = ids(all("licitacao", Filters.NONE, 50));
    var byServico = ids(all("servico", Filters.NONE, 50));

    assertThat(byLicitacao)
        .isNotEmpty()
        .containsExactlyInAnyOrderElementsOf(expected(v -> mentions(v, "licitação")));
    assertThat(byServico)
        .isNotEmpty()
        .containsExactlyInAnyOrderElementsOf(expected(v -> mentions(v, "serviço")));
  }

  @Test
  void theHeadlineMarksTheTermsAndIsNullWithoutText() {
    var withText = all("licitacao", Filters.NONE, 50);
    var withoutText = all("", Filters.NONE, 50);

    assertThat(withText).allSatisfy(r -> assertThat(r.snippet()).contains("<b>", "</b>"));
    assertThat(withoutText).allSatisfy(r -> assertThat(r.snippet()).isNull());
    assertThat(withoutText).allSatisfy(r -> assertThat(r.rank()).isNull());
  }

  @Test
  void eachFilterWorksAlone() {
    assertThat(idsOf(only(Uf.SP))).containsExactlyElementsOf(expected(v -> v.uf().equals("SP")));
    assertThat(idsOf(new Filters(null, new IbgeCode("3106200"), null, null, null, null, null)))
        .containsExactlyElementsOf(expected(v -> v.ibgeCode().equals("3106200")));
    assertThat(idsOf(new Filters(null, null, 6, null, null, null, null)))
        .containsExactlyElementsOf(expected(v -> v.modalityId() == 6));
    assertThat(idsOf(new Filters(null, null, null, Money.of("400000"), null, null, null)))
        .containsExactlyElementsOf(expected(v -> valued(v, "400000", "999999999")));
    assertThat(idsOf(new Filters(null, null, null, null, Money.of("30000"), null, null)))
        .containsExactlyElementsOf(expected(v -> valued(v, "0", "30000")));
    assertThat(idsOf(new Filters(null, null, null, null, null, true, null)))
        .containsExactlyElementsOf(expected(this::open));
    assertThat(idsOf(new Filters(null, null, null, null, null, false, null))).hasSize(1000);
    assertThat(idsOf(new Filters(null, null, null, null, null, null, "SUSPENDED")))
        .containsExactlyElementsOf(expected(v -> v.status().equals("SUSPENDED")));
  }

  @Test
  void openMeansPublishedWithAFutureClosingAndAnOpeningThatHasPassedOrIsUnknown() {
    var open = idsOf(new Filters(null, null, null, null, null, true, null));

    assertThat(open).isNotEmpty().isNotEqualTo(expected(v -> v.status().equals("PUBLISHED")));
    assertThat(open).doesNotContainAnyElementsOf(expected(v -> v.proposalClosingAt() == null));
    assertThat(open).doesNotContainAnyElementsOf(expected(v -> v.status().equals("SUSPENDED")));
  }

  @Test
  void aValueRangeKeepsBothBoundsAndDropsRowsWithoutValue() {
    var range =
        idsOf(new Filters(null, null, null, Money.of("100000"), Money.of("100000"), null, null));

    assertThat(range)
        .isNotEmpty()
        .containsExactlyElementsOf(expected(v -> valued(v, "100000", "100000")));
  }

  @Test
  void filtersCombineWithEachOtherAndWithTheText() {
    var combined =
        new Filters(Uf.MG, null, 4, Money.of("50000"), Money.of("400000"), true, "PUBLISHED");

    assertThat(idsOf(combined))
        .containsExactlyElementsOf(
            expected(
                v ->
                    v.uf().equals("MG")
                        && v.modalityId() == 4
                        && valued(v, "50000", "400000")
                        && open(v)));

    var withText =
        ids(
            all(
                "limpeza",
                new Filters(Uf.SP, null, null, Money.of("100000"), null, null, null),
                50));
    assertThat(withText)
        .isNotEmpty()
        .containsExactlyInAnyOrderElementsOf(
            expected(
                v ->
                    mentions(v, "limpeza")
                        && v.uf().equals("SP")
                        && valued(v, "100000", "999999999")));
  }

  @Test
  void paginatingATextQueryVisitsEveryMatchOnceInDecreasingRankOrder() {
    var pages = all("limpeza", Filters.NONE, 7);

    assertThat(ids(pages)).doesNotHaveDuplicates();
    assertThat(ids(pages))
        .containsExactlyInAnyOrderElementsOf(expected(v -> mentions(v, "limpeza")));
    assertThat(ids(pages)).containsExactlyElementsOf(ids(all("limpeza", Filters.NONE, 50)));
    for (var i = 1; i < pages.size(); i++) {
      var before = pages.get(i - 1);
      var after = pages.get(i);
      var strictlyAfter =
          before.rank() > after.rank()
              || (before.rank().equals(after.rank()) && before.id().compareTo(after.id()) > 0);
      assertThat(strictlyAfter).as("%s then %s", before, after).isTrue();
    }
    assertThat(pages.stream().map(RankedResult::rank).distinct().count()).isGreaterThan(2);
  }

  @Test
  void paginatingWithoutTextVisitsEveryRowOnceNewestFirst() {
    var pages = all("", Filters.NONE, 7);

    assertThat(ids(pages)).containsExactlyElementsOf(expected(v -> true));
    for (var i = 1; i < pages.size(); i++) {
      var before = pages.get(i - 1);
      var after = pages.get(i);
      var strictlyAfter =
          before.publishedAt().isAfter(after.publishedAt())
              || (before.publishedAt().equals(after.publishedAt())
                  && before.id().compareTo(after.id()) > 0);
      assertThat(strictlyAfter).as("%s then %s", before, after).isTrue();
    }
  }

  @Test
  void thereIsACursorOnlyWhenThereIsAnotherPage() {
    var sp = only(Uf.SP);
    var total = expected(v -> v.uf().equals("SP")).size();

    var first = search.search(new SearchQuery("", sp, null, 50));
    var last = first.items().get(49);

    assertThat(total).isGreaterThan(50);
    assertThat(first.nextCursor()).isEqualTo(new Cursor.ByPublished(last.publishedAt(), last.id()));
    var everything = search.search(new SearchQuery("", sp, null, 50)).items().size();
    assertThat(everything).isEqualTo(50);
    var exact = search.search(new SearchQuery("ambulancia", Filters.NONE, null, 2));
    assertThat(exact.items()).hasSize(2);
    assertThat(exact.nextCursor()).isNull();
    var rankCursor = search.search(new SearchQuery("limpeza", Filters.NONE, null, 3));
    var third = rankCursor.items().get(2);
    assertThat(rankCursor.nextCursor()).isEqualTo(new Cursor.ByRank(third.rank(), third.id()));
  }

  @Test
  void textWithNoSearchableTermGivesAnEmptyPageNotAnError() {
    for (var text : List.of("de da do", "!!!", "\"\"", "a o e")) {
      var page = search.search(new SearchQuery(text, Filters.NONE, null, 20));

      assertThat(page.items()).as(text).isEmpty();
      assertThat(page.nextCursor()).isNull();
    }
  }

  @Test
  void aBlankTextWithFiltersIsOrderedByPublicationDate() {
    var page = search.search(new SearchQuery("   ", only(Uf.RJ), null, 5));

    assertThat(ids(page.items()))
        .containsExactlyElementsOf(expected(v -> v.uf().equals("RJ")).subList(0, 5));
  }

  @Test
  void aTextThatMatchesNothingGivesAnEmptyPage() {
    var page = search.search(new SearchQuery("xyzzyplugh", Filters.NONE, null, 20));

    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void aTamperedCursorOrOneOfTheOtherKindIsRejected() {
    var valid = new Cursor.ByRank(0.5, id(1)).encode();

    assertThatThrownBy(() -> Cursor.decode(valid.substring(1)))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> new SearchQuery("", Filters.NONE, Cursor.decode(valid), 20))
        .isInstanceOf(InvalidCursorException.class);
    var published = new Cursor.ByPublished(Instant.now(), id(1));
    assertThatThrownBy(() -> new SearchQuery("limpeza", Filters.NONE, published, 20))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void aTwoMillionCharacterObjectIsSearchableAndItsHeadlineIsComputed() {
    var big =
        new ProcurementView(
            id(5000),
            "PUBLISHED",
            seed.get(0).situation(),
            "Orgao Gigante",
            null,
            "SP",
            "Santos",
            "3548500",
            6,
            "Pregão",
            null,
            null,
            null,
            now,
            now,
            "megaobjeto " + "texto de enchimento sem importancia ".repeat(60_000),
            "{}");
    assertThat(big.objectDescription().length()).isGreaterThan(2_000_000);
    SearchSeed.load(store, List.of(big));
    try {
      var found = search.search(new SearchQuery("megaobjeto", Filters.NONE, null, 20));

      assertThat(ids(found.items())).containsExactly(id(5000));
      assertThat(found.items().get(0).snippet()).contains("<b>megaobjeto</b>");
      assertThat(found.items().get(0).objectDescription()).hasSize(1000);
      var columns =
          readModel
              .query(new SearchQuery("megaobjeto", Filters.NONE, null, 20))
              .orElseThrow()
              .fetch()
              .fields();
      assertThat(columns).extracting(f -> f.getName()).doesNotContain("full_object");
    } finally {
      jdbc.update(
          "DELETE FROM search.procurement_search_view WHERE numero_controle_pncp = ?", id(5000));
    }
  }

  @Test
  void aModalityOutsideTheSmallintRangeMatchesNothingInsteadOfWrappingAround() {
    // 65542 wraps to 6 when narrowed to a short
    var page =
        search.search(
            new SearchQuery("", new Filters(null, null, 65_542, null, null, null, null), null, 20));

    assertThat(page.items()).isEmpty();
  }

  @Test
  void rankCursorPaginationHoldsTogetherWithTextAndFilters() {
    var filters = new Filters(Uf.SP, null, null, Money.of("50000"), null, null, null);
    var oracle =
        expected(
            v -> mentions(v, "limpeza") && v.uf().equals("SP") && valued(v, "50000", "999999999"));

    var pages = all("limpeza", filters, 5);

    assertThat(oracle.size()).isGreaterThan(10);
    assertThat(ids(pages)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(oracle);
    assertThat(ids(pages)).containsExactlyElementsOf(ids(all("limpeza", filters, 50)));
    for (var i = 1; i < pages.size(); i++) {
      var before = pages.get(i - 1);
      var after = pages.get(i);
      var strictlyAfter =
          before.rank() > after.rank()
              || (before.rank().equals(after.rank()) && before.id().compareTo(after.id()) > 0);
      assertThat(strictlyAfter).as("%s then %s", before, after).isTrue();
    }
  }

  @Test
  void theTextQueryUsesTheFullTextIndex() {
    // why: at 1 000 rows a sequential scan is cheaper than the index (the table is a few dozen
    // pages), so the plan is read with sequential scans off; at the volume of the benchmark the
    // planner picks the GIN index by itself
    var plan = plan(new SearchQuery("limpeza", Filters.NONE, null, 20), false);

    assertThat(plan).contains("Bitmap Index Scan on psv_fts_gin");
  }

  @Test
  void theQueryWithoutTextWalksThePublicationKeysetIndexInOrder() {
    var plan = plan(new SearchQuery("", Filters.NONE, null, 20), true);

    assertThat(plan).contains("Index Scan using psv_published_keyset").doesNotContain("Sort");
  }

  @Test
  void theQueryWithoutTextAndWithFiltersUsesTheFiltersKeysetIndex() {
    var filters = new Filters(Uf.SP, null, 6, null, null, null, null);
    var after = new Cursor.ByPublished(now, id(1));

    assertThat(plan(new SearchQuery("", filters, after, 20), true)).contains("psv_filters_keyset");
  }

  /** The plan of the statement as the planner picks it, or without sequential scans. */
  private String plan(SearchQuery query, boolean seqScan) {
    var sql = "EXPLAIN " + readModel.query(query).orElseThrow().getSQL(ParamType.INLINED);
    return jdbc.execute(
        (ConnectionCallback<String>)
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = " + (seqScan ? "on" : "off"));
                var lines = new ArrayList<String>();
                try (var rs = statement.executeQuery(sql)) {
                  while (rs.next()) {
                    lines.add(rs.getString(1));
                  }
                } finally {
                  // why: the pooled connection must not keep the setting for other tests
                  statement.execute("RESET enable_seqscan");
                }
                return String.join("\n", lines);
              }
            });
  }
}
