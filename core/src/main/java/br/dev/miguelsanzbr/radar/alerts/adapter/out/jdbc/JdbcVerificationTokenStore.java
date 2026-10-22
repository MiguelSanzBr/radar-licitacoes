package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcVerificationTokenStore implements VerificationTokenStore {

  private final JdbcClient jdbc;

  JdbcVerificationTokenStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(byte[] tokenHash, UUID subscriberId, Instant expiresAt) {
    var sql =
        jdbc.sql(
                "INSERT INTO alerts.verification_token (token_hash, subscriber_id, expires_at)"
                    + " VALUES (:hash, :subscriberId, :expiresAt)")
            .param("hash", tokenHash)
            .param("subscriberId", subscriberId);
    Db.timestamp(sql, "expiresAt", expiresAt).update();
  }

  @Override
  public Optional<UUID> consume(byte[] tokenHash, Instant now) {
    var sql =
        jdbc.sql(
                """
                UPDATE alerts.verification_token SET used_at = :now
                WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now
                RETURNING subscriber_id
                """)
            .param("hash", tokenHash);
    return Db.timestamp(sql, "now", now).query(UUID.class).optional();
  }

  @Override
  public void deleteBySubscriber(UUID subscriberId) {
    jdbc.sql("DELETE FROM alerts.verification_token WHERE subscriber_id = :s")
        .param("s", subscriberId)
        .update();
  }
}
