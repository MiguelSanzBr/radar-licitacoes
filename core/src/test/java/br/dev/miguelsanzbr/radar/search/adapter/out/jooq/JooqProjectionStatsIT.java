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
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The stats table follows the search view row by row: buckets move with the procurement. */
@OutboxSpringBootTest
class JooqProjectionStatsIT {

  private static final Instant MARCH = Instant.parse("2026-03-15T12:00:00Z");
  private static final Instant APRIL = Instant.parse("2026-04-15T12:00:00Z");

  @Autowired ProjectionStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  private List<Map<String, Object>> stats() {
    return jdbc.queryForList(
        "SELECT uf, modality_id, month::text AS month, value_band, count, estimated_sum::text AS sum"
            + " FROM search.procurement_stats_view ORDER BY uf, modality_id, month, value_band");
  }

  private Map<String, Object> only() {
    var rows = stats();
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private ProcurementView at(String id, String estimated, String uf, int modality, Instant when) {
    return rebucket(view(id, "object", estimated, 1), uf, modality, when);
  }

  @Test
  void aDiscoveredProcurementCountsInItsBucket() {
    store.project(discovered("d", at(id(1), "120000.00", "SP", 6, MARCH), 1), 0);

    assertThat(stats())
        .containsExactly(
            Map.of(
                "uf",
                "SP",
                "modality_id",
                6,
                "month",
                "2026-03-01",
                "value_band",
                2,
                "count",
                1,
                "sum",
                "120000.00"));
  }

  @Test
  void procurementsOfTheSameBucketAddUp() {
    store.project(discovered("a", at(id(1), "100.00", "SP", 6, MARCH), 1), 0);
    store.project(discovered("b", at(id(2), "250.50", "SP", 6, MARCH.plusSeconds(60)), 1), 1);

    assertThat(only())
        .satisfies(
            row -> {
              assertThat(row).containsEntry("count", 2).containsEntry("sum", "350.50");
            });
  }

  @Test
  void anUpdateThatChangesTheValueBandMovesTheProcurement() {
    store.project(discovered("d", at(id(1), "10000.00", "SP", 6, MARCH), 1), 0);

    store.project(updated("u", at(id(1), "800000.00", "SP", 6, MARCH), 2), 1);

    assertThat(only())
        .containsEntry("value_band", 3)
        .containsEntry("count", 1)
        .containsEntry("sum", "800000.00");
  }

  @Test
  void anUpdateThatChangesTheUfModalityAndMonthMovesTheProcurement() {
    store.project(discovered("d", at(id(1), "10.00", "SP", 6, MARCH), 1), 0);
    store.project(discovered("e", at(id(2), "20.00", "SP", 6, MARCH), 1), 1);

    store.project(updated("u", at(id(1), "10.00", "MG", 8, APRIL), 2), 2);

    assertThat(stats())
        .extracting(
            r ->
                r.get("uf")
                    + "/"
                    + r.get("modality_id")
                    + "/"
                    + r.get("month")
                    + "/"
                    + r.get("count")
                    + "/"
                    + r.get("sum"))
        .containsExactly("MG/8/2026-04-01/1/10.00", "SP/6/2026-03-01/1/20.00");
  }

  @Test
  void anUpdateInTheSameBucketOnlyChangesTheSum() {
    store.project(discovered("d", at(id(1), "10.00", "SP", 6, MARCH), 1), 0);

    store.project(updated("u", at(id(1), "15.00", "SP", 6, MARCH), 2), 1);

    assertThat(only()).containsEntry("count", 1).containsEntry("sum", "15.00");
  }

  @Test
  void aBucketThatEmptiesIsDeleted() {
    store.project(discovered("d", at(id(1), "10.00", "SP", 6, MARCH), 1), 0);

    store.project(updated("u", at(id(1), "10.00", "MG", 6, MARCH), 2), 1);

    assertThat(stats()).extracting(r -> r.get("uf")).containsExactly("MG");
  }

  @Test
  void theMonthIsTheOneOfSaoPauloNotOfUtc() {
    // 02:30Z of March 1st is still February 28th at 23:30 in Sao Paulo (UTC-3)
    store.project(
        discovered("d", at(id(1), "10.00", "SP", 6, Instant.parse("2026-03-01T02:30:00Z")), 1), 0);
    // 03:00Z is already March 1st at 00:00 there
    store.project(
        discovered("e", at(id(2), "10.00", "SP", 6, Instant.parse("2026-03-01T03:00:00Z")), 1), 1);

    assertThat(stats())
        .extracting(r -> r.get("month") + "/" + r.get("count"))
        .containsExactly("2026-02-01/1", "2026-03-01/1");
  }

  @Test
  void aProcurementWithoutUfFallsInTheXxBucketAndWithoutValueInBandZero() {
    store.project(discovered("d", at(id(1), null, null, 6, MARCH), 1), 0);

    assertThat(only())
        .containsEntry("uf", "XX")
        .containsEntry("value_band", 0)
        .containsEntry("sum", "0.00");
  }

  @Test
  void aStaleUpdateLeavesTheStatsAlone() {
    store.project(discovered("d", at(id(1), "10.00", "SP", 6, MARCH), 1), 0);
    store.project(updated("v3", at(id(1), "30.00", "SP", 6, MARCH), 3), 1);

    store.project(updated("v2", at(id(1), "20.00", "MG", 6, APRIL), 2), 2);

    assertThat(only()).containsEntry("uf", "SP").containsEntry("sum", "30.00");
  }

  @Test
  void aRedeliveredEventDoesNotCountTwice() {
    var event = discovered("d", at(id(1), "10.00", "SP", 6, MARCH), 1);
    store.project(event, 0);
    store.project(event, 1);

    assertThat(only()).containsEntry("count", 1);
  }

  @Test
  void situationChangesAndClosingDoNotMoveTheStats() {
    store.project(discovered("d", at(id(1), "10.00", "SP", 6, MARCH), 1), 0);
    var before = stats();

    store.project(situationChanged("s", id(1), 1, 1, 4), 1);
    store.project(closed("c", id(1), 2), 2);

    assertThat(stats()).isEqualTo(before);
  }
}
