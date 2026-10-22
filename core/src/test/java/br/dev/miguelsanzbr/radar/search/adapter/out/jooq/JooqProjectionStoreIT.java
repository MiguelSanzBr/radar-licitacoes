package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.APPLIED;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.DEFERRED;
import static br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome.DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@OutboxSpringBootTest
class JooqProjectionStoreIT {

  private static final String ID = id(1);

  @Autowired ProjectionStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint");
  }

  private Map<String, Object> searchRow() {
    return jdbc.queryForMap(
        "SELECT * FROM search.procurement_search_view WHERE numero_controle_pncp = ?", ID);
  }

  private Map<String, Object> detailRow() {
    return jdbc.queryForMap(
        "SELECT detail::text AS text, etag, source_version,"
            + " jsonb_array_length(detail->'history') AS history_size"
            + " FROM search.procurement_detail_view WHERE numero_controle_pncp = ?",
        ID);
  }

  private int processed() {
    return jdbc.queryForObject("SELECT count(*) FROM search.processed_event", Integer.class);
  }

  @Test
  void aDiscoveredProcurementIsProjectedIntoBothViews() {
    var event = discovered("d", view(ID, "Contratação de serviços", "120000.00", 1), 1);

    assertThat(store.project(event, 5)).isEqualTo(APPLIED);

    var search = searchRow();
    assertThat(search)
        .containsEntry("object_description", "Contratação de serviços")
        .containsEntry("organ_name", "Prefeitura de Teste")
        .containsEntry("unit_name", "Secretaria de Saúde")
        .containsEntry("uf", "SP")
        .containsEntry("ibge_code", "3550308")
        .containsEntry("municipality_name", "São Paulo")
        .containsEntry("modality_id", 6)
        .containsEntry("status", "PUBLISHED")
        .containsEntry("value_band", 2)
        .containsEntry("source_version", 1)
        .containsEntry("source", "pncp");
    assertThat(search.get("estimated_value")).hasToString("120000.00");
    var detail = detailRow();
    assertThat(detail).containsEntry("source_version", 1).containsEntry("history_size", 1);
    assertThat(detail.get("text").toString())
        .contains("\"objectDescription\": \"Contratação de serviços\"");
    assertThat(
            jdbc.queryForObject(
                "SELECT encode(sha256(convert_to(detail::text, 'UTF8')), 'hex') = etag"
                    + " FROM search.procurement_detail_view",
                Boolean.class))
        .isTrue();
    assertThat(processed()).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT last_event_id::text AS event, last_offset FROM search.projection_checkpoint"
                    + " WHERE projector = 'search-projector'"))
        .containsEntry("event", event.eventId().toString())
        .containsEntry("last_offset", 5L);
  }

  @Test
  void aProcurementWithoutValueIsBandZero() {
    store.project(discovered("d", view(ID, "object", null, 1), 1), 0);

    assertThat(searchRow()).containsEntry("value_band", 0);
    assertThat(searchRow().get("estimated_value")).isNull();
  }

  @Test
  void aRedeliveredEventIsSkipped() {
    var event = discovered("d", view(ID, "object", "10.00", 1), 1);
    store.project(event, 0);
    var before = detailRow();

    assertThat(store.project(event, 1)).isEqualTo(DUPLICATE);

    assertThat(detailRow()).isEqualTo(before);
    assertThat(processed()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT last_offset FROM search.projection_checkpoint", Long.class))
        .isZero();
  }

  @Test
  void anOlderVersionAfterANewerOneKeepsTheNewerContent() {
    store.project(discovered("v1", view(ID, "first", "10.00", 1), 1), 0);
    store.project(updated("v3", view(ID, "third", "10.00", 1, 3, T0.plusSeconds(30)), 3), 1);

    var outcome =
        store.project(updated("v2", view(ID, "second", "10.00", 1, 2, T0.plusSeconds(20)), 2), 2);

    assertThat(outcome).isEqualTo(APPLIED);
    assertThat(searchRow())
        .containsEntry("object_description", "third")
        .containsEntry("source_version", 3);
    assertThat(detailRow().get("text").toString()).contains("\"third\"").doesNotContain("second");
    assertThat(processed()).isEqualTo(3);
  }

  @Test
  void anEqualVersionNeverOverwrites() {
    store.project(discovered("a", view(ID, "first", "10.00", 1), 1), 0);

    store.project(updated("b", view(ID, "other", "10.00", 1), 1), 1);

    assertThat(searchRow()).containsEntry("object_description", "first");
    assertThat(detailRow().get("text").toString()).doesNotContain("other");
  }

  @Test
  void aNewerSnapshotReplacesTheContentButKeepsTheHistory() {
    store.project(discovered("d", view(ID, "first", "10.00", 1), 1), 0);
    store.project(situationChanged("s", ID, 1, 1, 4), 1);

    store.project(updated("u", view(ID, "second", "20.00", 4, 2, T0.plusSeconds(60)), 2), 2);

    assertThat(searchRow())
        .containsEntry("object_description", "second")
        .containsEntry("status", "SUSPENDED");
    assertThat(detailRow()).containsEntry("history_size", 2);
  }

  @Test
  void aSituationChangeBeforeTheDiscoveryIsDeferredAndAppliedOnRedelivery() {
    var change = situationChanged("s", ID, 1, 1, 4);

    assertThat(store.project(change, 0)).isEqualTo(DEFERRED);
    assertThat(processed()).isZero();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM search.projection_checkpoint", Integer.class))
        .isZero();

    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 1);
    assertThat(store.project(change, 2)).isEqualTo(APPLIED);

    assertThat(searchRow()).containsEntry("status", "SUSPENDED");
    assertThat(detailRow()).containsEntry("history_size", 2);
    assertThat(detailRow().get("text").toString()).contains("\"status\": \"SUSPENDED\"");
    assertThat(processed()).isEqualTo(2);
  }

  @Test
  void aSituationChangeAppendsToTheHistoryAndBumpsTheGuard() {
    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 0);
    store.project(updated("u", view(ID, "object", "10.00", 4, 2, T0.plusSeconds(60)), 2), 1);

    assertThat(store.project(situationChanged("s", ID, 2, 1, 4), 2)).isEqualTo(APPLIED);

    assertThat(searchRow()).containsEntry("status", "SUSPENDED").containsEntry("source_version", 2);
    var history =
        jdbc.queryForList(
            "SELECT h->'from'->>'id' AS from_id, h->'to'->>'id' AS to_id, h->>'unexpected' AS unexpected"
                + " FROM search.procurement_detail_view,"
                + " jsonb_array_elements(detail->'history') WITH ORDINALITY AS t(h, n)"
                + " ORDER BY n");
    assertThat(history)
        .extracting(row -> row.get("from_id") + ">" + row.get("to_id"))
        .containsExactly("null>1", "1>4");
  }

  @Test
  void aSituationChangeBackToPublishedRestoresTheStatus() {
    store.project(discovered("d", view(ID, "object", "10.00", 4), 1), 0);

    store.project(situationChanged("s", ID, 1, 4, 1), 1);

    assertThat(searchRow()).containsEntry("status", "PUBLISHED");
  }

  @Test
  void aProcurementWithoutUnitLocationOrWindowIsProjected() {
    var full = view(ID, "object", "10.00", 1);
    var bare =
        new ProcurementView(
            ID,
            full.status(),
            full.situation(),
            full.organName(),
            null,
            null,
            null,
            null,
            full.modalityId(),
            full.modalityName(),
            null,
            null,
            null,
            full.publishedAt(),
            full.pncpUpdatedAt(),
            full.objectDescription(),
            full.json());

    assertThat(store.project(discovered("d", bare, 1), 0)).isEqualTo(APPLIED);

    assertThat(searchRow())
        .containsEntry("unit_name", null)
        .containsEntry("uf", null)
        .containsEntry("municipality_name", null)
        .containsEntry("proposal_opening_at", null)
        .containsEntry("proposal_closing_at", null);
  }

  @Test
  void aSituationOutsideTheKnownStatusesKeepsTheStatus() {
    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 0);

    store.project(situationChanged("s", ID, 1, 1, 99), 1);

    assertThat(searchRow()).containsEntry("status", "PUBLISHED");
    assertThat(detailRow()).containsEntry("history_size", 2);
  }

  @Test
  void aSituationChangeOlderThanTheProjectionIsRecordedButChangesNothing() {
    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 0);
    store.project(updated("u", view(ID, "object", "10.00", 1, 3, T0.plusSeconds(60)), 3), 1);

    assertThat(store.project(situationChanged("s", ID, 2, 1, 2), 2)).isEqualTo(APPLIED);

    assertThat(searchRow()).containsEntry("status", "PUBLISHED").containsEntry("source_version", 3);
    assertThat(detailRow()).containsEntry("history_size", 1);
    assertThat(processed()).isEqualTo(3);
  }

  @Test
  void aClosedEventAddsClosedAtToTheDetailAndNothingToTheSearchRow() {
    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 0);
    var before = searchRow();

    assertThat(store.project(closed("c", ID, 2), 1)).isEqualTo(APPLIED);

    var after = searchRow();
    assertThat(after.get("source_version")).isEqualTo(2);
    assertThat(after.get("status")).isEqualTo(before.get("status"));
    assertThat(after.get("proposal_closing_at")).isEqualTo(before.get("proposal_closing_at"));
    assertThat(detailRow().get("text").toString())
        .contains("\"closedAt\": \"2026-10-21T13:00:00Z\"");
    assertThat(detailRow()).containsEntry("source_version", 2).containsEntry("history_size", 1);
  }

  @Test
  void closedAtSurvivesALaterSnapshot() {
    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 0);
    store.project(closed("c", ID, 2), 1);

    store.project(updated("u", view(ID, "changed", "10.00", 1, 3, T0.plusSeconds(60)), 3), 2);

    assertThat(detailRow().get("text").toString())
        .contains("\"closedAt\"")
        .contains("\"objectDescription\": \"changed\"");
  }

  @Test
  void aClosedEventBeforeTheDiscoveryIsDeferred() {
    assertThat(store.project(closed("c", ID, 2), 0)).isEqualTo(DEFERRED);

    assertThat(processed()).isZero();
  }

  @Test
  void aStaleClosedEventChangesNothing() {
    store.project(discovered("d", view(ID, "object", "10.00", 1), 1), 0);
    store.project(updated("u", view(ID, "object", "10.00", 1, 3, T0.plusSeconds(60)), 3), 1);

    assertThat(store.project(closed("c", ID, 2), 2)).isEqualTo(APPLIED);

    assertThat(detailRow().get("text").toString()).doesNotContain("closedAt");
    assertThat(searchRow()).containsEntry("source_version", 3);
  }

  @Test
  void accentsAndInflectionAreIgnoredByTheVector() {
    store.project(discovered("d", view(ID, "Contratação de serviços", "10.00", 1), 1), 0);

    assertThat(
            jdbc.queryForObject(
                "SELECT search_vector @@ websearch_to_tsquery('search.portuguese_unaccent',"
                    + " 'contratacao servico') FROM search.procurement_search_view",
                Boolean.class))
        .isTrue();
  }

  @Test
  void theVectorWeighsObjectOrganAndPlaceDifferently() {
    store.project(discovered("d", view(ID, "Contratação de serviços", "10.00", 1), 1), 0);

    List<String> hits =
        jdbc.queryForList(
            """
            SELECT w FROM (VALUES ('servico:A', 'object'), ('prefeitura:B', 'organ'),
              ('saude:B', 'unit'), ('paulo:C', 'municipality'), ('pregao:C', 'modality'),
              ('servico:B', 'wrong')) AS q(expr, w)
            WHERE (SELECT search_vector FROM search.procurement_search_view)
                  @@ to_tsquery('search.portuguese_unaccent', expr)
            """,
            String.class);

    assertThat(hits)
        .containsExactlyInAnyOrder("object", "organ", "unit", "municipality", "modality");
  }

  @Test
  void anObjectOfTwoMillionCharactersProjectsWithoutError() {
    var huge = "licitacao ".repeat(200_000);

    var outcome = store.project(discovered("d", view(ID, huge, "10.00", 1), 1), 0);

    assertThat(outcome).isEqualTo(APPLIED);
    assertThat(
            jdbc.queryForObject(
                "SELECT length(object_description) FROM search.procurement_search_view",
                Integer.class))
        .isEqualTo(2_000_000);
    assertThat(
            jdbc.queryForObject(
                "SELECT search_vector @@ to_tsquery('search.portuguese_unaccent', 'licitacao')"
                    + " FROM search.procurement_search_view",
                Boolean.class))
        .isTrue();
  }

  @Test
  void aFailureAfterTheInboxInsertRollsTheInboxBackToo() {
    var broken = discovered("d", view(ID, "object", "10.00", 1), 1);
    store.project(broken, 0);
    jdbc.execute("ALTER TABLE search.projection_checkpoint RENAME TO projection_checkpoint_x");
    try {
      var next = discovered("e", view(id(2), "object", "10.00", 1), 1);
      assertThatThrownBy(() -> store.project(next, 1)).isInstanceOf(RuntimeException.class);
      assertThat(processed()).isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE search.projection_checkpoint_x RENAME TO projection_checkpoint");
    }
  }
}
