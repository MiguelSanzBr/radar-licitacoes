package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.EmailAlreadyRegisteredException;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.domain.Subscriber;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSubscriberRepository implements SubscriberRepository {

  private static final String SELECT =
      "SELECT id, email, consent_at, verified_at, deleted_at, created_at FROM alerts.subscriber";

  private final JdbcClient jdbc;

  JdbcSubscriberRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(Subscriber s) {
    var sql =
        jdbc.sql(
                """
                INSERT INTO alerts.subscriber (id, email, consent_at, verified_at, deleted_at, created_at)
                VALUES (:id, :email, :consentAt, :verifiedAt, :deletedAt, :createdAt)
                ON CONFLICT (id) DO UPDATE SET
                  email = EXCLUDED.email, verified_at = EXCLUDED.verified_at,
                  deleted_at = EXCLUDED.deleted_at
                """)
            .param("id", s.id())
            .param("email", s.email());
    sql = Db.timestamp(sql, "consentAt", s.consentAt());
    sql = Db.timestamp(sql, "verifiedAt", s.verifiedAt());
    sql = Db.timestamp(sql, "deletedAt", s.deletedAt());
    sql = Db.timestamp(sql, "createdAt", s.createdAt());
    try {
      sql.update();
    } catch (DuplicateKeyException e) {
      throw new EmailAlreadyRegisteredException();
    }
  }

  @Override
  public Optional<Subscriber> lockById(UUID id) {
    return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<Subscriber> lockByEmail(String email) {
    return jdbc.sql(SELECT + " WHERE email = CAST(:email AS citext) FOR UPDATE")
        .param("email", email)
        .query(this::map)
        .optional();
  }

  private Subscriber map(ResultSet rs, int row) throws SQLException {
    return new Subscriber(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        Db.instant(rs, "consent_at"),
        Db.instant(rs, "verified_at"),
        Db.instant(rs, "deleted_at"),
        Db.instant(rs, "created_at"));
  }

  @Override
  public int purgeUnverifiedBefore(Instant cutoff) {
    // Children first; the subscriber table is deleted last so the subselect keeps matching.
    var stale =
        "SELECT id FROM alerts.subscriber WHERE verified_at IS NULL AND created_at < :cutoff";
    for (var child :
        new String[] {
          "DELETE FROM alerts.alert_delivery WHERE saved_search_id IN"
              + " (SELECT id FROM alerts.saved_search WHERE subscriber_id IN ("
              + stale
              + "))",
          "DELETE FROM alerts.saved_search WHERE subscriber_id IN (" + stale + ")",
          "DELETE FROM alerts.verification_token WHERE subscriber_id IN (" + stale + ")",
          "DELETE FROM alerts.idempotency_key WHERE subscriber_id IN (" + stale + ")"
        }) {
      Db.timestamp(jdbc.sql(child), "cutoff", cutoff).update();
    }
    return Db.timestamp(
            jdbc.sql(
                "DELETE FROM alerts.subscriber WHERE verified_at IS NULL AND created_at < :cutoff"),
            "cutoff",
            cutoff)
        .update();
  }
}
