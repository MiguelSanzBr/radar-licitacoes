package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertFilters;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcSavedSearchRepository implements SavedSearchRepository {

  private static final String COLUMNS =
      "id, subscriber_id, name, query_text, filters::text AS filters, frequency, active, created_at";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  JdbcSavedSearchRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void save(SavedSearch s) {
    // Only `active` changes after creation, so a conflict updates just that column.
    var sql =
        jdbc.sql(
                """
                INSERT INTO alerts.saved_search
                  (id, subscriber_id, name, query_text, filters, frequency, active, created_at)
                VALUES (:id, :subscriberId, :name, :text, CAST(:filters AS jsonb), :frequency,
                        :active, :createdAt)
                ON CONFLICT (id) DO UPDATE SET active = EXCLUDED.active
                """)
            .param("id", s.id())
            .param("subscriberId", s.subscriberId())
            .param("name", s.name())
            .param("text", s.query().text())
            .param("filters", json.writeValueAsString(s.query().filters()))
            .param("frequency", s.frequency().name())
            .param("active", s.active());
    Db.timestamp(sql, "createdAt", s.createdAt()).update();
  }

  @Override
  public Optional<SavedSearch> findActiveOwned(UUID subscriberId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM alerts.saved_search"
                + " WHERE id = :id AND subscriber_id = :subscriberId AND active")
        .param("id", id)
        .param("subscriberId", subscriberId)
        .query(this::map)
        .optional();
  }

  @Override
  public List<SavedSearch> listActive(UUID subscriberId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM alerts.saved_search"
                + " WHERE subscriber_id = :subscriberId AND active ORDER BY created_at, id")
        .param("subscriberId", subscriberId)
        .query(this::map)
        .list();
  }

  @Override
  public long countActive(UUID subscriberId) {
    return jdbc.sql("SELECT count(*) FROM alerts.saved_search WHERE subscriber_id = :s AND active")
        .param("s", subscriberId)
        .query(Long.class)
        .single();
  }

  @Override
  public void anonymizeAll(UUID subscriberId) {
    jdbc.sql(
            "UPDATE alerts.saved_search SET active = false, name = '(apagada)', query_text = ''"
                + " WHERE subscriber_id = :s")
        .param("s", subscriberId)
        .update();
  }

  @Override
  public void deleteUnsentDeliveries(UUID subscriberId) {
    jdbc.sql(
            """
            DELETE FROM alerts.alert_delivery d USING alerts.saved_search s
            WHERE d.saved_search_id = s.id AND s.subscriber_id = :s AND d.sent_at IS NULL
            """)
        .param("s", subscriberId)
        .update();
  }

  private SavedSearch map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    var filters = json.readValue(rs.getString("filters"), AlertFilters.class);
    return new SavedSearch(
        rs.getObject("id", UUID.class),
        rs.getObject("subscriber_id", UUID.class),
        rs.getString("name"),
        new SavedQuery(rs.getString("query_text"), filters),
        Frequency.valueOf(rs.getString("frequency")),
        rs.getBoolean("active"),
        Db.instant(rs, "created_at"));
  }
}
