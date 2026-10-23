package br.dev.miguelsanzbr.radar.identity.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys.Result;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.Tier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcApiKeysIT extends PostgresIT {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired ApiKeys keys;

  private static ApiKey.Issued issue(String owner, Tier tier) {
    return ApiKey.issue(
        UUID.randomUUID(), owner, tier, NOW, RandomGenerator.of("L64X128MixRandom"));
  }

  @Test
  void anActiveKeyIsFoundByItsHashWithEveryField() {
    var issued = issue("kc-1", Tier.PRO);
    assertThat(keys.addWithinLimit(issued.key(), 5)).isEqualTo(Result.ADDED);

    assertThat(keys.findActive(issued.key().keyHash())).contains(issued.key());
    assertThat(keys.findActive(ApiKey.hash("rk_00000000_other"))).isEmpty();
  }

  @Test
  void onlyTheHashAndThePrefixAreStored() {
    var issued = issue("kc-1", Tier.FREE);
    keys.addWithinLimit(issued.key(), 5);

    var row =
        jdbc.sql(
                "SELECT encode(key_hash, 'hex') AS hash, prefix, tier, revoked_at"
                    + " FROM identity.api_key")
            .query()
            .singleRow();

    assertThat(row)
        .containsEntry("hash", issued.key().keyHash())
        .containsEntry("prefix", issued.key().prefix())
        .containsEntry("tier", "free")
        .containsEntry("revoked_at", null);
    assertThat(row.values().toString()).doesNotContain(issued.secret());
  }

  @Test
  void revokedKeysStopAuthenticatingAndOnlyTheOwnerCanRevoke() {
    var issued = issue("kc-1", Tier.FREE);
    keys.addWithinLimit(issued.key(), 5);

    assertThat(keys.revoke("kc-2", issued.key().prefix(), NOW)).isFalse();
    assertThat(keys.findActive(issued.key().keyHash())).isPresent();
    assertThat(keys.revoke("kc-1", issued.key().prefix(), NOW)).isTrue();
    assertThat(keys.findActive(issued.key().keyHash())).isEmpty();
    assertThat(keys.revoke("kc-1", issued.key().prefix(), NOW)).isFalse();
    assertThat(
            jdbc.sql("SELECT count(*) FROM identity.api_key WHERE revoked_at IS NOT NULL")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void theLimitCountsActiveKeysOfTheOwnerOnly() {
    var first = issue("kc-1", Tier.FREE);
    assertThat(keys.addWithinLimit(first.key(), 2)).isEqualTo(Result.ADDED);
    assertThat(keys.addWithinLimit(issue("kc-1", Tier.FREE).key(), 2)).isEqualTo(Result.ADDED);
    assertThat(keys.addWithinLimit(issue("kc-1", Tier.FREE).key(), 2))
        .isEqualTo(Result.LIMIT_REACHED);
    assertThat(keys.addWithinLimit(issue("kc-2", Tier.FREE).key(), 2)).isEqualTo(Result.ADDED);

    keys.revoke("kc-1", first.key().prefix(), NOW);

    assertThat(keys.addWithinLimit(issue("kc-1", Tier.FREE).key(), 2)).isEqualTo(Result.ADDED);
  }

  @Test
  void parallelCreatesCannotOvershootTheLimit() throws Exception {
    var threads = 16;
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(threads)) {
      var results =
          IntStream.range(0, threads)
              .mapToObj(
                  i ->
                      pool.submit(
                          (Callable<Boolean>)
                              () -> {
                                var key = issue("kc-1", Tier.FREE).key();
                                start.await();
                                return keys.addWithinLimit(key, 5) == Result.ADDED;
                              }))
              .toList();
      start.countDown();
      var created = 0;
      for (var result : results) {
        created += result.get() ? 1 : 0;
      }

      assertThat(created).isEqualTo(5);
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM identity.api_key WHERE owner_subject = 'kc-1'")
                .query(Long.class)
                .single())
        .isEqualTo(5);
  }

  @Test
  void aPrefixTheOwnerAlreadyHasIsADuplicateEvenWhenTheOldKeyIsRevoked() {
    var first = issue("kc-1", Tier.FREE);
    keys.addWithinLimit(first.key(), 5);
    var twin =
        new ApiKey(
            UUID.randomUUID(),
            "kc-1",
            ApiKey.hash("another"),
            first.key().prefix(),
            Tier.FREE,
            NOW);

    assertThat(keys.addWithinLimit(twin, 5)).isEqualTo(Result.DUPLICATE);
    keys.revoke("kc-1", first.key().prefix(), NOW);
    assertThat(keys.addWithinLimit(twin, 5)).isEqualTo(Result.DUPLICATE);
    // the same prefix under another owner is fine
    var other =
        new ApiKey(
            UUID.randomUUID(), "kc-2", ApiKey.hash("x"), first.key().prefix(), Tier.FREE, NOW);
    assertThat(keys.addWithinLimit(other, 5)).isEqualTo(Result.ADDED);
  }
}
