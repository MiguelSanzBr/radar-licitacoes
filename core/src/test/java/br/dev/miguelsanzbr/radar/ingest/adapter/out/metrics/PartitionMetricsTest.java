package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.PartitionMaintenance;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PartitionMetricsTest {

  private final PartitionMaintenance maintenance = mock(PartitionMaintenance.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void exposesTheFutureMonthsThatExistAsAGaugeReadAtScrapeTime() {
    new PartitionMetrics(maintenance).bindTo(registry);

    when(maintenance.monthsAhead()).thenReturn(3);
    assertThat(registry.get("ingest_partitions_ahead").gauge().value()).isEqualTo(3);

    when(maintenance.monthsAhead()).thenReturn(0);
    assertThat(registry.get("ingest_partitions_ahead").gauge().value()).isZero();
  }
}
