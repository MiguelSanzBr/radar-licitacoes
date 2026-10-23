package br.dev.miguelsanzbr.radar.alerts.adapter;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Direct inserts of subscribers and saved searches for the matcher tests. */
public final class MatcherData {

  private MatcherData() {}

  public static UUID subscriber(JdbcClient jdbc, boolean verified, boolean deleted) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO alerts.subscriber (id, email, consent_at, verified_at, deleted_at, created_at)
            VALUES (:id, CAST(:email AS citext), now(),
                    CASE WHEN :verified THEN now() END, CASE WHEN :deleted THEN now() END, now())
            """)
        .param("id", id)
        .param("email", id + "@matcher.test")
        .param("verified", verified)
        .param("deleted", deleted)
        .update();
    return id;
  }

  public static UUID subscriber(JdbcClient jdbc) {
    return subscriber(jdbc, true, false);
  }

  public static UUID search(
      JdbcClient jdbc, UUID subscriberId, String queryText, String filtersJson, boolean active) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO alerts.saved_search
              (id, subscriber_id, name, query_text, filters, frequency, active, created_at)
            VALUES (:id, :subscriber, 'busca', :text, CAST(:filters AS jsonb), 'INSTANT', :active, now())
            """)
        .param("id", id)
        .param("subscriber", subscriberId)
        .param("text", queryText)
        .param("filters", filtersJson)
        .param("active", active)
        .update();
    return id;
  }

  public static UUID search(
      JdbcClient jdbc, UUID subscriberId, String queryText, String filtersJson) {
    return search(jdbc, subscriberId, queryText, filtersJson, true);
  }
}
