package br.dev.miguelsanzbr.radar.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SleeperTest {

  @Test
  void systemSleeperBlocksForTheRequestedTime() {
    long before = System.nanoTime();

    Sleeper.system().sleep(Duration.ofMillis(30));

    assertThat(Duration.ofNanos(System.nanoTime() - before))
        .isGreaterThanOrEqualTo(Duration.ofMillis(25));
  }

  @Test
  void interruptionKeepsTheFlagAndFailsLoudly() {
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> Sleeper.system().sleep(Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Thread.interrupted()).isTrue();
  }
}
