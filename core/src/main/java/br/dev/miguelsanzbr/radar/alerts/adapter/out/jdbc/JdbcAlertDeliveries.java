package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.AlertDeliveries;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAlertDeliveries implements AlertDeliveries {

  private final JdbcClient jdbc;

  JdbcAlertDeliveries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insertIfAbsent(UUID savedSearchId, String procurementId, Instant matchedAt) {
    var sql =
        jdbc.sql(
                """
                INSERT INTO alerts.alert_delivery (saved_search_id, procurement_id, matched_at)
                VALUES (:savedSearch, :procurement, :matchedAt)
                ON CONFLICT DO NOTHING
                """)
            .param("savedSearch", savedSearchId)
            .param("procurement", procurementId);
    return Db.timestamp(sql, "matchedAt", matchedAt).update() == 1;
  }
}
