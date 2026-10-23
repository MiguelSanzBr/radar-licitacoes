package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.ProcessedEvents;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProcessedEvents implements ProcessedEvents {

  private final JdbcClient jdbc;

  JdbcProcessedEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean firstTime(String consumer, UUID eventId, Instant at) {
    var sql =
        jdbc.sql(
                """
                INSERT INTO alerts.processed_event (consumer, event_id, processed_at)
                VALUES (:consumer, :eventId, :at)
                ON CONFLICT DO NOTHING
                """)
            .param("consumer", consumer)
            .param("eventId", eventId);
    return Db.timestamp(sql, "at", at).update() == 1;
  }
}
