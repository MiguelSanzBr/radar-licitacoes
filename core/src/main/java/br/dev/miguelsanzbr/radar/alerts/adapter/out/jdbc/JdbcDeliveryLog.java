package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The notifier's view of {@code alert_delivery}. A row is visible only while its search is active
 * and its subscriber is verified and not erased, the same rule the matcher applies.
 */
@Repository
class JdbcDeliveryLog implements DeliveryLog {

  private static final String RECEIVABLE =
      """
      FROM alerts.alert_delivery d
      JOIN alerts.saved_search s ON s.id = d.saved_search_id
      JOIN alerts.subscriber u ON u.id = s.subscriber_id
      WHERE s.active AND u.verified_at IS NOT NULL AND u.deleted_at IS NULL
      """;

  private final JdbcClient jdbc;

  JdbcDeliveryLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Delivery> find(UUID savedSearchId, String procurementId) {
    return jdbc.sql(
            "SELECT d.matched_at, d.sent_at, u.email, s.name "
                + RECEIVABLE
                + " AND d.saved_search_id = :search AND d.procurement_id = :procurement")
        .param("search", savedSearchId)
        .param("procurement", procurementId)
        .query(
            (rs, row) ->
                new Delivery(
                    Db.instant(rs, "matched_at"),
                    Db.instant(rs, "sent_at"),
                    rs.getString("email"),
                    rs.getString("name")))
        .optional();
  }

  @Override
  public void markSent(UUID savedSearchId, String procurementId, Instant at) {
    var sql =
        jdbc.sql(
                """
                UPDATE alerts.alert_delivery
                SET sent_at = :at, object_description = NULL, organ_name = NULL, uf = NULL,
                    municipality = NULL, estimated_value = NULL, proposal_closing_at = NULL,
                    detail_path = NULL
                WHERE saved_search_id = :search AND procurement_id = :procurement AND sent_at IS NULL
                """)
            .param("search", savedSearchId)
            .param("procurement", procurementId);
    Db.timestamp(sql, "at", at).update();
  }

  @Override
  public void keepForDigest(AlertMatched alert) {
    var sql =
        jdbc.sql(
                """
                UPDATE alerts.alert_delivery
                SET object_description = :description, organ_name = :organ, uf = :uf,
                    municipality = :municipality, estimated_value = :value, detail_path = :path,
                    proposal_closing_at = :closing
                WHERE saved_search_id = :search AND procurement_id = :procurement AND sent_at IS NULL
                """)
            .param("description", alert.objectDescription())
            .param("organ", alert.organName())
            .param("uf", alert.uf())
            .param("municipality", alert.municipality())
            .param("value", alert.estimatedValue() == null ? null : alert.estimatedValue().amount())
            .param("path", alert.detailPath())
            .param("search", alert.savedSearchId())
            .param("procurement", alert.procurementId());
    Db.timestamp(sql, "closing", alert.proposalClosingAt()).update();
  }

  // shortcut: the whole unsent queue is read at once, upgrade to paging by subscriber when a
  // day's queue stops fitting in memory
  @Override
  public List<Queued> unsentDigest() {
    return jdbc.sql(
            "SELECT d.saved_search_id, d.procurement_id, d.object_description, d.organ_name, d.uf,"
                + " d.municipality, d.estimated_value, d.proposal_closing_at, d.detail_path,"
                + " s.name, s.subscriber_id, u.email "
                + RECEIVABLE
                + " AND d.sent_at IS NULL AND d.detail_path IS NOT NULL"
                + " ORDER BY s.subscriber_id, d.matched_at, d.procurement_id")
        .query(
            (rs, row) -> {
              var subscriber = rs.getObject("subscriber_id", UUID.class);
              var value = rs.getBigDecimal("estimated_value");
              return new Queued(
                  subscriber,
                  rs.getString("email"),
                  rs.getString("name"),
                  new AlertMatched(
                      rs.getObject("saved_search_id", UUID.class),
                      subscriber,
                      rs.getString("procurement_id"),
                      Frequency.DAILY,
                      rs.getString("object_description"),
                      rs.getString("organ_name"),
                      rs.getString("uf"),
                      rs.getString("municipality"),
                      value == null ? null : new Money(value),
                      Db.instant(rs, "proposal_closing_at"),
                      rs.getString("detail_path")));
            })
        .list();
  }
}
