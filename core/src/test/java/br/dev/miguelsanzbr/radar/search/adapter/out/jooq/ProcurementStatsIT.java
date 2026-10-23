package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.rebucket;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.GroupBy;
import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.application.StatsRow;
import br.dev.miguelsanzbr.radar.search.application.StatsUseCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan proof of the incremental stats: after thousands of events with updates that move
 * procurements between buckets, every grouping equals a direct {@code GROUP BY} over the search
 * view, and still does after the same events are delivered again.
 */
@OutboxSpringBootTest
class ProcurementStatsIT {

  private static final String[] UFS = {"SP", "MG", "RJ", "BA", "PR", null};
  private static final String[] VALUES = {
    null,
    "10.00",
    "49999.99",
    "50000.00",
    "120000.50",
    "499999.99",
    "500000.00",
    "4999999.99",
    "5000000.00",
    "98000000.00"
  };
  private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");
  private static final int AGGREGATES = 600;

  @Autowired ProjectionStore store;
  @Autowired StatsUseCase stats;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  /**
   * Discovered, then updates that change the value, the uf, the modality or the publication instant
   * (some near a month end, where Sao Paulo and UTC disagree), situation changes, closing, and an
   * older update arriving after the newer ones.
   */
  static List<ProcurementEvent> events() {
    var random = new Random(2026);
    var events = new ArrayList<ProcurementEvent>();
    var stale = new ArrayList<ProcurementEvent>();
    for (var i = 0; i < AGGREGATES; i++) {
      var id = id(i);
      var view = view(id, "Objeto " + i, VALUES[random.nextInt(VALUES.length)], 1);
      var current = random(view, random);
      events.add(discovered("d" + i, current, 1));
      var updates = random.nextInt(4);
      for (var v = 2; v < 2 + updates; v++) {
        var next =
            random(view(id, "Objeto " + i, VALUES[random.nextInt(VALUES.length)], 1), random);
        var event = updated("u" + i + "-" + v, next, v);
        (v == 2 && random.nextInt(4) == 0 ? stale : events).add(event);
      }
      if (random.nextBoolean()) {
        events.add(situationChanged("s" + i, id, 1 + updates, 1, 4));
      }
      events.add(closed("c" + i, id, 2 + updates));
    }
    events.addAll(stale);
    return events;
  }

  private static ProcurementView random(ProcurementView base, Random random) {
    var nearMonthEnd = random.nextInt(3) == 0;
    var month = random.nextInt(6);
    var published =
        FIRST
            .plusSeconds(2_678_400L * month)
            .plusSeconds(
                nearMonthEnd ? -3 * 3600L + random.nextInt(7200) : random.nextInt(2_000_000));
    return rebucket(base, UFS[random.nextInt(UFS.length)], 1 + random.nextInt(8), published);
  }

  private void projectAll(List<ProcurementEvent> events) {
    var offset = 0L;
    for (var event : events) {
      var outcome = store.project(event, offset++);
      assertThat(outcome).isIn(ProjectionOutcome.APPLIED, ProjectionOutcome.DUPLICATE);
    }
  }

  /** What a direct GROUP BY over the search view answers; the key is the same text. */
  private List<StatsRow> direct(GroupBy groupBy) {
    var expression =
        switch (groupBy) {
          case UF -> "coalesce(uf, 'XX')";
          case MODALITY -> "modality_id";
          case MONTH -> "date_trunc('month', published_at AT TIME ZONE 'America/Sao_Paulo')::date";
          case VALUE_BAND -> "value_band";
        };
    var key =
        groupBy == GroupBy.MONTH ? "to_char(" + expression + ", 'YYYY-MM')" : expression + "::text";
    return jdbc.query(
        "SELECT "
            + key
            + " AS k, count(*) AS c, coalesce(sum(estimated_value), 0) AS s"
            + " FROM search.procurement_search_view GROUP BY "
            + expression
            + " ORDER BY "
            + expression,
        (rs, n) -> new StatsRow(rs.getString("k"), rs.getLong("c"), rs.getBigDecimal("s")));
  }

  private void assertStatsMatchTheSearchView() {
    for (var groupBy : GroupBy.values()) {
      var expected = direct(groupBy);
      var actual = stats.stats(groupBy);
      assertThat(actual).as(groupBy.name()).hasSameSizeAs(expected);
      for (var i = 0; i < expected.size(); i++) {
        assertThat(actual.get(i).key()).as(groupBy + " key " + i).isEqualTo(expected.get(i).key());
        assertThat(actual.get(i).count())
            .as(groupBy + " count of " + expected.get(i).key())
            .isEqualTo(expected.get(i).count());
        assertThat(actual.get(i).estimatedSum())
            .as(groupBy + " sum of " + expected.get(i).key())
            .isEqualByComparingTo(expected.get(i).estimatedSum());
      }
    }
  }

  @Test
  void everyGroupingMatchesADirectGroupByOverTheSearchView() {
    var events = events();
    assertThat(events).hasSizeGreaterThanOrEqualTo(2_000);

    projectAll(events);

    assertThat(direct(GroupBy.UF)).hasSizeGreaterThan(3);
    assertThat(direct(GroupBy.MONTH)).hasSizeGreaterThan(3);
    assertThat(
            jdbc.queryForObject("SELECT sum(count) FROM search.procurement_stats_view", Long.class))
        .isEqualTo(AGGREGATES);
    assertStatsMatchTheSearchView();
  }

  @Test
  void deliveringTheSameEventsAgainChangesNothing() {
    var events = events();
    projectAll(events);
    var before =
        jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4");

    projectAll(events);

    assertThat(jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4"))
        .isEqualTo(before);
    assertStatsMatchTheSearchView();
  }

  /** What the replay does: wipe the read model, then project the log in batches of 500. */
  @Test
  void rebuildingFromTheEventsGivesTheSameStatsAgain() {
    var events = events();
    projectAll(events);
    var before =
        jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4");

    store.reset();
    assertThat(stats.stats(GroupBy.UF)).isEmpty();
    for (var from = 0; from < events.size(); from += 500) {
      var batch = new ArrayList<OffsetEvent>();
      for (var i = from; i < Math.min(from + 500, events.size()); i++) {
        batch.add(new OffsetEvent(events.get(i), i));
      }
      store.projectAll(batch);
    }

    assertThat(jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4"))
        .isEqualTo(before);
    assertStatsMatchTheSearchView();
  }

  /** Databases projected before the stats existed get them from the migration. */
  @Test
  void theBackfillBuildsTheStatsFromTheSearchViewOnlyWhenTheyAreEmpty() throws Exception {
    projectAll(events());
    var expected =
        jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4");
    var backfill =
        new String(
            getClass()
                .getResourceAsStream("/db/migration/V15__search_stats_backfill.sql")
                .readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);

    jdbc.execute(backfill);
    assertThat(jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4"))
        .as("a populated table is left alone")
        .isEqualTo(expected);

    jdbc.execute("TRUNCATE search.procurement_stats_view");
    jdbc.execute(backfill);
    assertThat(jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4"))
        .isEqualTo(expected);
    jdbc.execute(backfill);
    assertThat(jdbc.queryForList("SELECT * FROM search.procurement_stats_view ORDER BY 1, 2, 3, 4"))
        .isEqualTo(expected);
  }

  @Test
  void anEmptyReadModelHasNoStats() {
    for (var groupBy : GroupBy.values()) {
      assertThat(stats.stats(groupBy)).isEmpty();
    }
  }
}
