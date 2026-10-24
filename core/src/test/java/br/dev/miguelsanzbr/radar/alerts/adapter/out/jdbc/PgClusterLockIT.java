package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.alerts.application.port.ClusterLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PgClusterLockIT extends PostgresIT {

  @Autowired ClusterLock lock;

  @Test
  void aRunHoldingTheLockMakesASecondRunSkipUntilItCommits() throws Exception {
    var duringFirst = new AtomicBoolean(true);
    var secondRan = new AtomicBoolean();

    var firstRan =
        lock.tryRun(
            "test.lock",
            () ->
                duringFirst.set(
                    CompletableFuture.supplyAsync(
                            () -> lock.tryRun("test.lock", () -> secondRan.set(true)))
                        .join()));

    assertThat(firstRan).isTrue();
    assertThat(duringFirst).isFalse();
    assertThat(secondRan).isFalse();
    assertThat(lock.tryRun("test.lock", () -> secondRan.set(true))).isTrue();
    assertThat(secondRan).isTrue();
  }

  @Test
  void differentNamesDoNotBlockEachOther() {
    var other = new AtomicBoolean();

    lock.tryRun(
        "test.one",
        () ->
            other.set(
                CompletableFuture.supplyAsync(() -> lock.tryRun("test.two", () -> {})).join()));

    assertThat(other).isTrue();
  }

  @Test
  void theLockIsReleasedWhenTheWorkFails() {
    assertThatThrownBy(
            () ->
                lock.tryRun(
                    "test.lock",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(lock.tryRun("test.lock", () -> {})).isTrue();
  }
}
