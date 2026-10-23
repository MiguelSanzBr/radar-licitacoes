package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.eventId;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.APPLIED;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.DEFERRED;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.OffsetEvent;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The batch and the wipe that the replay builds on, against the real read side. */
@OutboxSpringBootTest
class JooqProjectionReplayIT {

  @Autowired ProjectionStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.procurement_stats_view, search.processed_event,"
            + " search.projection_checkpoint");
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM search." + table, Integer.class);
  }

  @Test
  void aBatchIsProjectedInOrderInOneTransactionWithAnOutcomePerEvent() {
    var first = discovered("d1", view(id(1), "Contratação de serviços", "1000.00", 1), 1);
    var second = discovered("d2", view(id(2), "Aquisição de material", "2000.00", 1), 1);

    var outcomes =
        store.projectAll(
            List.of(
                new OffsetEvent(first, 0),
                new OffsetEvent(situationChanged("s1", id(1), 1, 1, 4), 1),
                new OffsetEvent(second, 2),
                new OffsetEvent(first, 3)));

    assertThat(outcomes).containsExactly(APPLIED, APPLIED, APPLIED, DUPLICATE);
    assertThat(count("procurement_search_view")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM search.procurement_search_view WHERE numero_controle_pncp = ?",
                String.class,
                id(1)))
        .isEqualTo("SUSPENDED");
    assertThat(
            jdbc.queryForObject("SELECT last_offset FROM search.projection_checkpoint", Long.class))
        .isEqualTo(2L);
  }

  @Test
  void aDeferredEventInTheMiddleOfABatchDoesNotUndoItsNeighbours() {
    var before = discovered("d1", view(id(1), "Contratação de serviços", null, 1), 1);
    var orphan = closed("c-orphan", id(77), 3);
    var after = discovered("d2", view(id(2), "Aquisição de material", null, 1), 1);

    var outcomes =
        store.projectAll(
            List.of(
                new OffsetEvent(before, 0), new OffsetEvent(orphan, 1), new OffsetEvent(after, 2)));

    assertThat(outcomes).containsExactly(APPLIED, DEFERRED, APPLIED);
    assertThat(count("procurement_search_view")).isEqualTo(2);
    assertThat(count("processed_event")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM search.processed_event WHERE event_id = ?",
                Integer.class,
                eventId("c-orphan")))
        .isZero();
  }

  @Test
  void resetWipesTheReadSideAndTheProjectorInboxButKeepsOtherConsumers() {
    store.projectAll(
        List.of(new OffsetEvent(discovered("d1", view(id(1), "Contratação", "1000.00", 1), 1), 0)));
    jdbc.update(
        "INSERT INTO search.procurement_stats_view VALUES ('SP', 6, DATE '2026-10-01', 1, 3, 10.00)");
    jdbc.update(
        "INSERT INTO search.processed_event VALUES ('other-consumer', ?, now())", eventId("x"));

    store.reset();

    assertThat(count("procurement_search_view")).isZero();
    assertThat(count("procurement_detail_view")).isZero();
    assertThat(count("procurement_stats_view")).isZero();
    assertThat(count("projection_checkpoint")).isZero();
    assertThat(jdbc.queryForList("SELECT consumer FROM search.processed_event", String.class))
        .containsExactly("other-consumer");
  }
}
