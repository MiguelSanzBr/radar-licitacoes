package br.dev.miguelsanzbr.radar.alerts.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard.Stored;
import br.dev.miguelsanzbr.radar.alerts.application.ManageSavedSearchUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertFilters;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The losing twin must wait for the winner's commit, then replay instead of running again. */
class IdempotencyConcurrencyIT extends PostgresIT {

  @Autowired IdempotencyGuard guard;
  @Autowired ManageSavedSearchUseCase manage;
  @Autowired RegisterSubscriberUseCase register;

  @Test
  void secondCallBlocksUntilTheFirstCommitsThenReplays() throws Exception {
    var owner = register.register("fulano@example.com", true).id();
    var hash = Sha256.hash("body");
    var winnerClaimed = new CountDownLatch(1);
    var loserRuns = new AtomicInteger();

    var winner =
        CompletableFuture.supplyAsync(
            () ->
                guard.run(
                    owner,
                    "key-0001",
                    hash,
                    () -> {
                      winnerClaimed.countDown();
                      pause();
                      var created =
                          manage.create(
                              owner,
                              "Notebooks",
                              new SavedQuery("notebook", AlertFilters.NONE),
                              Frequency.DAILY);
                      return new Stored(201, "{\"id\":\"" + created.id() + "\"}");
                    }));
    assertThat(winnerClaimed.await(10, TimeUnit.SECONDS)).isTrue();

    var loser =
        guard.run(
            owner,
            "key-0001",
            hash,
            () -> {
              loserRuns.incrementAndGet();
              return new Stored(500, "{}");
            });

    assertThat(loser.replayed()).isTrue();
    var won = winner.get(10, TimeUnit.SECONDS).stored();
    assertThat(loser.stored().status()).isEqualTo(won.status());
    // jsonb reformats whitespace, so compare ignoring it.
    assertThat(loser.stored().body().replace(" ", "")).isEqualTo(won.body());
    assertThat(loserRuns).hasValue(0);
    assertThat(jdbc.sql("SELECT count(*) FROM alerts.saved_search").query(Long.class).single())
        .isEqualTo(1);
  }

  private static void pause() {
    try {
      Thread.sleep(600);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
