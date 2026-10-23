package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.ingest.application.PartitionMaintenance;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * {@code ingest_partitions_ahead}: future months of {@code ingest.procurement} that exist, up to
 * the configured horizon. It is read from the catalog at each scrape, so a dropped partition shows
 * before the next daily run.
 */
@Component
class PartitionMetrics implements MeterBinder {

  private final PartitionMaintenance maintenance;

  PartitionMetrics(PartitionMaintenance maintenance) {
    this.maintenance = maintenance;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("ingest_partitions_ahead", maintenance, PartitionMaintenance::monthsAhead)
        .description("Future monthly partitions of ingest.procurement that exist")
        .strongReference(true)
        .register(registry);
  }
}
