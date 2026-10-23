package br.dev.miguelsanzbr.radar.ops.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ops.domain.OutboxBacklog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeterOutboxStatsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MeterOutboxStats stats = new MeterOutboxStats(registry);

  @Test
  void theBacklogIsReadFromTheGaugesTheOutboxPublishes() {
    Gauge.builder("outbox_pending", () -> 4).register(registry);
    Gauge.builder("outbox_oldest_age_seconds", () -> 2.5).register(registry);
    Gauge.builder("outbox_poison_pending", () -> 1).register(registry);

    assertThat(stats.read()).isEqualTo(new OutboxBacklog(4, 2.5, 1));
  }

  @Test
  void aMissingGaugeIsAnError() {
    assertThatThrownBy(stats::read)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox_pending");
  }

  @Test
  void aGaugeThatCouldNotBeReadIsAnError() {
    Gauge.builder("outbox_pending", () -> Double.NaN).register(registry);
    Gauge.builder("outbox_oldest_age_seconds", () -> 0).register(registry);
    Gauge.builder("outbox_poison_pending", () -> 0).register(registry);

    assertThatThrownBy(stats::read)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox_pending");
  }
}
