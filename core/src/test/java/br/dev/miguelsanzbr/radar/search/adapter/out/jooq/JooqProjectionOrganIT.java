package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.withOrgan;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code search.organ} follows the search view row by row, in the transaction of the projector. */
@OutboxSpringBootTest
class JooqProjectionOrganIT {

  private static final String CHAPECO = "Prefeitura Municipal de Chapecó";
  private static final String CAMARA = "Câmara Municipal de Chapecó";

  @Autowired ProjectionStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view, search.organ");
  }

  private Map<String, Object> organs() {
    var rows = jdbc.queryForList("SELECT organ_name, procurement_count FROM search.organ");
    return rows.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                r -> (String) r.get("organ_name"), r -> r.get("procurement_count")));
  }

  private ProcurementView of(int n, String organ) {
    return withOrgan(view(id(n), "Objeto " + n, "1000.00", 1), organ);
  }

  @Test
  void eachProcurementCountsOnceForItsOrgan() {
    store.project(discovered("a", of(1, CHAPECO), 1), 0);
    store.project(discovered("b", of(2, CHAPECO), 1), 1);
    store.project(discovered("c", of(3, CAMARA), 1), 2);

    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 2), Map.entry(CAMARA, 1));
  }

  @Test
  void anUpdateOfTheSameOrganDoesNotCountAgain() {
    store.project(discovered("a", of(1, CHAPECO), 1), 0);

    store.project(updated("u", of(1, CHAPECO), 2), 1);

    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 1));
  }

  @Test
  void anUpdateThatChangesTheOrganMovesTheProcurementAndDropsTheEmptyOne() {
    store.project(discovered("a", of(1, CHAPECO), 1), 0);
    store.project(discovered("b", of(2, CHAPECO), 1), 1);

    store.project(updated("u1", of(1, CAMARA), 2), 2);
    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 1), Map.entry(CAMARA, 1));

    store.project(updated("u2", of(2, CAMARA), 2), 3);
    assertThat(organs()).containsOnly(Map.entry(CAMARA, 2));
  }

  @Test
  void anOlderVersionAndARedeliveryLeaveTheOrgansAlone() {
    var first = discovered("a", of(1, CHAPECO), 5);
    store.project(first, 0);

    store.project(updated("old", of(1, CAMARA), 3), 1);
    store.project(first, 2);

    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 1));
  }

  @Test
  void aSituationChangeAndAClosingDoNotTouchTheOrgans() {
    store.project(discovered("a", of(1, CHAPECO), 1), 0);

    store.project(closed("c", id(1), 2), 1);

    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 1));
  }

  @Test
  void aBatchKeepsTheCountsExact() {
    store.projectAll(
        List.of(
            new OffsetEvent(discovered("a", of(1, CHAPECO), 1), 0),
            new OffsetEvent(discovered("b", of(2, CHAPECO), 1), 1),
            new OffsetEvent(updated("u", of(2, CAMARA), 2), 2)));

    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 1), Map.entry(CAMARA, 1));
  }

  @Test
  void resetEmptiesTheOrgansAndTheReplayRebuildsThem() {
    store.project(discovered("a", of(1, CHAPECO), 1), 0);

    store.reset();
    assertThat(organs()).isEmpty();

    store.project(discovered("a", of(1, CHAPECO), 1), 0);
    assertThat(organs()).containsOnly(Map.entry(CHAPECO, 1));
  }

  @Test
  void theBackfillBuildsTheOrgansFromTheSearchViewOnlyWhenTheyAreEmpty() throws Exception {
    store.project(discovered("a", of(1, CHAPECO), 1), 0);
    store.project(discovered("b", of(2, CHAPECO), 1), 1);
    store.project(discovered("c", of(3, CAMARA), 1), 2);
    var backfill =
        new String(
            getClass().getResourceAsStream("/db/migration/V16__search_organ.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    var expected = organs();
    // the migration creates the table: replay only its data statement against the existing one
    var insert = backfill.substring(backfill.indexOf("INSERT INTO"));

    jdbc.execute(insert);
    assertThat(organs()).as("a populated table is left alone").isEqualTo(expected);

    jdbc.execute("TRUNCATE search.organ");
    jdbc.execute(insert);
    assertThat(organs()).isEqualTo(expected);
  }
}
