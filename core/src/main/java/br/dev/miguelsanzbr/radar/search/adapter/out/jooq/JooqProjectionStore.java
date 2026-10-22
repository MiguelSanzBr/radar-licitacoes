package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated.Tables.PROCESSED_EVENT;
import static br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated.Tables.PROJECTION_CHECKPOINT;

import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.application.Situation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbox and projection in one transaction of the read side manager. The version guard ({@code
 * source_version}) means an older event never overwrites a newer state; a situation or closing
 * event may share the version of the snapshot it follows, so it applies from equal up.
 */
@Component
class JooqProjectionStore implements ProjectionStore {

  static final String PROJECTOR = "search-projector";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String UPSERT_SEARCH =
      """
      INSERT INTO search.procurement_search_view AS psv
        (numero_controle_pncp, search_vector, object_description, organ_name, unit_name, uf,
         ibge_code, municipality_name, modality_id, status, estimated_value, value_band,
         proposal_opening_at, proposal_closing_at, published_at, pncp_updated_at, source_version,
         projected_at)
      VALUES
        (?,
         setweight(to_tsvector('search.portuguese_unaccent', left(?, 100000)), 'A')
           || setweight(to_tsvector('search.portuguese_unaccent', ?), 'B')
           || setweight(to_tsvector('search.portuguese_unaccent', ?), 'C'),
         ?, ?, ?, ?, ?, ?, ?::smallint, ?, ?::numeric, ?::smallint,
         ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::integer, now())
      ON CONFLICT (numero_controle_pncp) DO UPDATE SET
        search_vector = excluded.search_vector, object_description = excluded.object_description,
        organ_name = excluded.organ_name, unit_name = excluded.unit_name, uf = excluded.uf,
        ibge_code = excluded.ibge_code, municipality_name = excluded.municipality_name,
        modality_id = excluded.modality_id, status = excluded.status,
        estimated_value = excluded.estimated_value, value_band = excluded.value_band,
        proposal_opening_at = excluded.proposal_opening_at,
        proposal_closing_at = excluded.proposal_closing_at, published_at = excluded.published_at,
        pncp_updated_at = excluded.pncp_updated_at, source_version = excluded.source_version,
        projected_at = excluded.projected_at
      WHERE excluded.source_version > psv.source_version
      """;

  /**
   * The detail is the snapshot plus what only events add: the situation history and {@code
   * closedAt} are carried over from the stored row. The etag is the hash of the canonical text of
   * the stored jsonb.
   */
  private static final String UPSERT_DETAIL =
      """
      WITH merged AS (
        SELECT ?::jsonb
          || jsonb_build_object('history', coalesce(o.detail->'history', ?::jsonb))
          || CASE WHEN o.detail->'closedAt' IS NULL THEN jsonb_build_object()
                  ELSE jsonb_build_object('closedAt', o.detail->'closedAt') END AS detail
        FROM (SELECT 1) AS one
        LEFT JOIN (SELECT detail FROM search.procurement_detail_view
                   WHERE numero_controle_pncp = ?) AS o ON true)
      INSERT INTO search.procurement_detail_view AS pdv
        (numero_controle_pncp, detail, etag, source_version, updated_at)
      SELECT ?, detail, encode(sha256(convert_to(detail::text, 'UTF8')), 'hex'), ?::integer, now()
      FROM merged
      ON CONFLICT (numero_controle_pncp) DO UPDATE SET
        detail = excluded.detail, etag = excluded.etag, source_version = excluded.source_version,
        updated_at = excluded.updated_at
      WHERE excluded.source_version > pdv.source_version
      """;

  private static final String LOCK_DETAIL =
      "SELECT source_version FROM search.procurement_detail_view"
          + " WHERE numero_controle_pncp = ? FOR UPDATE";

  private static final String PATCH_SEARCH =
      """
      UPDATE search.procurement_search_view
      SET status = coalesce(?::text, status), source_version = ?::integer, projected_at = now()
      WHERE numero_controle_pncp = ?
      """;

  private static final String PATCH_DETAIL =
      """
      UPDATE search.procurement_detail_view AS pdv
      SET detail = patched.detail,
          etag = encode(sha256(convert_to(patched.detail::text, 'UTF8')), 'hex'),
          source_version = ?::integer, updated_at = now()
      FROM (SELECT (detail || ?::jsonb)
                   || jsonb_build_object('history', coalesce(detail->'history', '[]'::jsonb) || ?::jsonb)
                   AS detail
            FROM search.procurement_detail_view WHERE numero_controle_pncp = ?) AS patched
      WHERE pdv.numero_controle_pncp = ?
      """;

  private record HistoryEntry(
      Situation from,
      Situation to,
      boolean unexpected,
      Instant pncpUpdatedAt,
      Instant recordedAt) {}

  private final DSLContext dsl;
  private final TransactionTemplate transaction;

  JooqProjectionStore(
      @Qualifier("searchDsl") DSLContext dsl,
      @Qualifier("searchTransactionManager") PlatformTransactionManager transactionManager) {
    this.dsl = dsl;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public ProjectionOutcome project(ProcurementEvent event, long offset) {
    return transaction.execute(
        tx -> {
          var inserted =
              dsl.insertInto(PROCESSED_EVENT)
                  .set(PROCESSED_EVENT.CONSUMER, PROJECTOR)
                  .set(PROCESSED_EVENT.EVENT_ID, event.eventId())
                  .set(PROCESSED_EVENT.PROCESSED_AT, DSL.currentOffsetDateTime())
                  .onConflictDoNothing()
                  .execute();
          if (inserted == 0) {
            return ProjectionOutcome.DUPLICATE;
          }
          var applied =
              switch (event) {
                case ProcurementEvent.Discovered d ->
                    upsert(
                        d.snapshot(),
                        d.aggregateVersion(),
                        history(
                            new HistoryEntry(
                                null,
                                d.snapshot().situation(),
                                false,
                                d.snapshot().pncpUpdatedAt(),
                                d.occurredAt())));
                case ProcurementEvent.Updated u -> upsert(u.snapshot(), u.aggregateVersion(), "[]");
                case ProcurementEvent.SituationChanged s ->
                    patch(
                        s,
                        statusOf(s.to().id()),
                        situationPatch(s.to()),
                        history(
                            new HistoryEntry(
                                s.from(),
                                s.to(),
                                s.unexpected(),
                                s.pncpUpdatedAt(),
                                s.occurredAt())));
                case ProcurementEvent.Closed c ->
                    patch(c, null, Map.of("closedAt", c.occurredAt()), "[]");
              };
          if (!applied) {
            // why: nothing was written for this event, so it must stay unseen for a redelivery
            tx.setRollbackOnly();
            return ProjectionOutcome.DEFERRED;
          }
          checkpoint(event, offset);
          return ProjectionOutcome.APPLIED;
        });
  }

  /** The guard keeps a stored row of the same or a later version untouched. */
  private boolean upsert(ProcurementView view, long version, String initialHistory) {
    dsl.execute(
        UPSERT_SEARCH,
        view.id(),
        view.objectDescription(),
        view.organName() + " " + orEmpty(view.unitName()),
        orEmpty(view.municipalityName()) + " " + view.modalityName(),
        view.objectDescription(),
        view.organName(),
        view.unitName(),
        view.uf(),
        view.ibgeCode(),
        view.municipalityName(),
        view.modalityId(),
        view.status(),
        view.estimatedValue(),
        view.valueBand(),
        utc(view.proposalOpeningAt()),
        utc(view.proposalClosingAt()),
        utc(view.publishedAt()),
        utc(view.pncpUpdatedAt()),
        version);
    dsl.execute(UPSERT_DETAIL, view.json(), initialHistory, view.id(), view.id(), version);
    return true;
  }

  /**
   * False when the procurement is not projected yet. A newer stored version makes the event stale:
   * it is dropped and still counts as processed.
   */
  private boolean patch(
      ProcurementEvent event, String status, Map<String, Object> detailPatch, String history) {
    var stored = dsl.fetchOptional(LOCK_DETAIL, event.aggregateId());
    if (stored.isEmpty()) {
      return false;
    }
    if (event.aggregateVersion() >= stored.get().get(0, Integer.class)) {
      var id = event.aggregateId();
      dsl.execute(PATCH_SEARCH, status, event.aggregateVersion(), id);
      dsl.execute(
          PATCH_DETAIL,
          event.aggregateVersion(),
          JSON.writeValueAsString(detailPatch),
          history,
          id,
          id);
    }
    return true;
  }

  private void checkpoint(ProcurementEvent event, long offset) {
    dsl.insertInto(PROJECTION_CHECKPOINT)
        .set(PROJECTION_CHECKPOINT.PROJECTOR, PROJECTOR)
        .set(PROJECTION_CHECKPOINT.LAST_EVENT_ID, event.eventId())
        .set(PROJECTION_CHECKPOINT.LAST_OFFSET, offset)
        .set(PROJECTION_CHECKPOINT.UPDATED_AT, DSL.currentOffsetDateTime())
        .onConflict(PROJECTION_CHECKPOINT.PROJECTOR)
        .doUpdate()
        .set(PROJECTION_CHECKPOINT.LAST_EVENT_ID, event.eventId())
        .set(PROJECTION_CHECKPOINT.LAST_OFFSET, offset)
        .set(PROJECTION_CHECKPOINT.UPDATED_AT, DSL.currentOffsetDateTime())
        .execute();
  }

  /** Internal status of a PNCP situation id; null for one the read side does not know. */
  private static String statusOf(int situationId) {
    return switch (situationId) {
      case 1 -> "PUBLISHED";
      case 2 -> "REVOKED";
      case 3 -> "ANNULLED";
      case 4 -> "SUSPENDED";
      default -> null;
    };
  }

  /** The detail carries the status next to the situation; an unknown situation leaves it alone. */
  private static Map<String, Object> situationPatch(Situation to) {
    var status = statusOf(to.id());
    return status == null ? Map.of("situation", to) : Map.of("status", status, "situation", to);
  }

  private static String history(HistoryEntry entry) {
    return JSON.writeValueAsString(List.of(entry));
  }

  private static String orEmpty(String text) {
    return text == null ? "" : text;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
