package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

  private final JdbcClient jdbc;

  JdbcIdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean claim(
      UUID subscriberId, String key, byte[] requestHash, Instant now, Instant expiredBefore) {
    Db.timestamp(
            jdbc.sql(
                    "DELETE FROM alerts.idempotency_key"
                        + " WHERE subscriber_id = :s AND key = :key AND created_at < :expiredBefore")
                .param("s", subscriberId)
                .param("key", key),
            "expiredBefore",
            expiredBefore)
        .update();
    // A concurrent insert of the same key blocks here until that transaction ends.
    var insert =
        jdbc.sql(
                """
                INSERT INTO alerts.idempotency_key (key, subscriber_id, request_hash, created_at)
                VALUES (:key, :s, :hash, :now)
                ON CONFLICT DO NOTHING
                """)
            .param("key", key)
            .param("s", subscriberId)
            .param("hash", requestHash);
    return Db.timestamp(insert, "now", now).update() == 1;
  }

  @Override
  public Optional<Recorded> find(UUID subscriberId, String key, byte[] requestHash) {
    return jdbc.sql(
            """
            SELECT request_hash = :hash AS same, response_status, response_body::text AS body
            FROM alerts.idempotency_key WHERE subscriber_id = :s AND key = :key
            """)
        .param("hash", requestHash)
        .param("s", subscriberId)
        .param("key", key)
        .query(
            (rs, n) ->
                new Recorded(
                    rs.getBoolean("same"), rs.getInt("response_status"), rs.getString("body")))
        .optional();
  }

  @Override
  public void complete(UUID subscriberId, String key, int status, String body) {
    jdbc.sql(
            """
            UPDATE alerts.idempotency_key
            SET response_status = :status, response_body = CAST(:body AS jsonb)
            WHERE subscriber_id = :s AND key = :key
            """)
        .param("status", status)
        .param("body", body)
        .param("s", subscriberId)
        .param("key", key)
        .update();
  }

  @Override
  public void deleteBySubscriber(UUID subscriberId) {
    jdbc.sql("DELETE FROM alerts.idempotency_key WHERE subscriber_id = :s")
        .param("s", subscriberId)
        .update();
  }

  @Override
  public int purgeBefore(Instant cutoff) {
    return Db.timestamp(
            jdbc.sql("DELETE FROM alerts.idempotency_key WHERE created_at < :cutoff"),
            "cutoff",
            cutoff)
        .update();
  }
}
