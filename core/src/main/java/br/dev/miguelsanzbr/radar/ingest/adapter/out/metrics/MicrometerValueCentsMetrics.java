package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.ingest.application.port.ValueCentsMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * {@code ingest_value_cents_pending_rows}: rows of {@code ingest.procurement} still waiting for
 * their cents, as the backfill last counted them. The gauge only exists once the job has run.
 */
@Component
class MicrometerValueCentsMetrics implements ValueCentsMetrics {

  private final MeterRegistry registry;
  private final AtomicLong pending = new AtomicLong();

  MicrometerValueCentsMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void pending(long rows) {
    pending.set(rows);
    registry.gauge("ingest_value_cents_pending_rows", pending);
  }
}
