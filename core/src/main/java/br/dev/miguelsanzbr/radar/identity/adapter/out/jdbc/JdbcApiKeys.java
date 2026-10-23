package br.dev.miguelsanzbr.radar.identity.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.Tier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class JdbcApiKeys implements ApiKeys {

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;

  JdbcApiKeys(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public Result addWithinLimit(ApiKey key, int max) {
    try {
      return transaction.execute(
          status -> {
            // The advisory lock serializes the creates of one owner (there is no parent row to
            // lock),
            // taken before the count so the count sees every key committed by the previous
            // holder.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:owner, 0))")
                .param("owner", key.ownerSubject())
                .query()
                .listOfRows();
            var active =
                jdbc.sql(
                        "SELECT count(*) FROM identity.api_key"
                            + " WHERE owner_subject = :owner AND revoked_at IS NULL")
                    .param("owner", key.ownerSubject())
                    .query(Long.class)
                    .single();
            if (active >= max) {
              return Result.LIMIT_REACHED;
            }
            jdbc.sql(
                    """
                      INSERT INTO identity.api_key (id, owner_subject, key_hash, prefix, tier, created_at)
                      VALUES (:id, :owner, decode(:hash, 'hex'), :prefix, :tier, :createdAt)
                      """)
                .param("id", key.id())
                .param("owner", key.ownerSubject())
                .param("hash", key.keyHash())
                .param("prefix", key.prefix())
                .param("tier", key.tier().value())
                .param("createdAt", OffsetDateTime.ofInstant(key.createdAt(), ZoneOffset.UTC))
                .update();
            return Result.ADDED;
          });
    } catch (DuplicateKeyException e) {
      // (owner, prefix) or the hash is taken; the transaction is already rolled back
      return Result.DUPLICATE;
    }
  }

  @Override
  public Optional<ApiKey> findActive(String keyHash) {
    return jdbc.sql(
            """
            SELECT id, owner_subject, encode(key_hash, 'hex') AS key_hash, prefix, tier, created_at
            FROM identity.api_key
            WHERE key_hash = decode(:hash, 'hex') AND revoked_at IS NULL
            """)
        .param("hash", keyHash)
        .query(JdbcApiKeys::map)
        .optional();
  }

  @Override
  public boolean revoke(String ownerSubject, String prefix, Instant at) {
    return jdbc.sql(
                "UPDATE identity.api_key SET revoked_at = :at"
                    + " WHERE owner_subject = :owner AND prefix = :prefix AND revoked_at IS NULL")
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .param("owner", ownerSubject)
            .param("prefix", prefix)
            .update()
        > 0;
  }

  private static ApiKey map(ResultSet rs, int row) throws SQLException {
    return new ApiKey(
        rs.getObject("id", UUID.class),
        rs.getString("owner_subject"),
        rs.getString("key_hash"),
        rs.getString("prefix"),
        Tier.of(rs.getString("tier")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
