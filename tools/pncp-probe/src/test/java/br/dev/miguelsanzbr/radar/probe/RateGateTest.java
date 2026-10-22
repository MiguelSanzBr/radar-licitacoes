package br.dev.miguelsanzbr.radar.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateGateTest {

  private static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");

  @Test
  void firstAcquireDoesNotWaitAndNextOnesAreSpacedByTheInterval() {
    FakeTime time = new FakeTime(T0);
    RateGate gate = new RateGate(time, time, Duration.ofSeconds(2));

    gate.acquire();
    assertThat(time.instant()).isEqualTo(T0);
    gate.acquire();
    assertThat(time.instant()).isEqualTo(T0.plusSeconds(2));
    gate.acquire();
    assertThat(time.instant()).isEqualTo(T0.plusSeconds(4));
  }

  @Test
  void doesNotWaitWhenTheIntervalAlreadyElapsed() {
    FakeTime time = new FakeTime(T0);
    RateGate gate = new RateGate(time, time, Duration.ofSeconds(2));

    gate.acquire();
    time.sleep(Duration.ofSeconds(5));
    gate.acquire();

    assertThat(time.instant()).isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void neverAllowsMoreThanFourRequestsPerSecond() {
    FakeTime time = new FakeTime(T0);
    RateGate gate = new RateGate(time, time, Duration.ofSeconds(2));

    gate.interval(Duration.ofMillis(10));
    gate.acquire();
    gate.acquire();

    assertThat(time.instant()).isEqualTo(T0.plusMillis(250));
  }

  @Test
  void intervalCanBeRaisedAgain() {
    FakeTime time = new FakeTime(T0);
    RateGate gate = new RateGate(time, time, Duration.ofMillis(250));

    gate.interval(Duration.ofSeconds(2));
    gate.acquire();
    gate.acquire();

    assertThat(time.instant()).isEqualTo(T0.plusSeconds(2));
  }
}
