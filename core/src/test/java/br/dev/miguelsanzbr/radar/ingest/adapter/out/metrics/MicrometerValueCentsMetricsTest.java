package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerValueCentsMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerValueCentsMetrics metrics = new MicrometerValueCentsMetrics(registry);

  @Test
  void theGaugeIsAbsentUntilTheFirstReport() {
    assertThat(registry.find("ingest_value_cents_pending_rows").gauge()).isNull();
  }

  @Test
  void theGaugeFollowsTheLastReportedCount() {
    metrics.pending(120);
    assertThat(registry.get("ingest_value_cents_pending_rows").gauge().value()).isEqualTo(120);

    metrics.pending(0);
    assertThat(registry.get("ingest_value_cents_pending_rows").gauge().value()).isZero();
    assertThat(registry.find("ingest_value_cents_pending_rows").gauges()).hasSize(1);
  }
}
