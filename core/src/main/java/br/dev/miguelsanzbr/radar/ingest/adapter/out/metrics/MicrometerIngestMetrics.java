package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestMetrics;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** {@code ingest_items_total{kind, outcome}}: notices per kind of run and what became of them. */
@Component
class MicrometerIngestMetrics implements IngestMetrics {

  private final MeterRegistry registry;

  MicrometerIngestMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void itemsProcessed(IngestionKind kind, int discovered, int updated, int unchanged) {
    var tag = kind.name().toLowerCase(Locale.ROOT);
    count(tag, "discovered", discovered);
    count(tag, "updated", updated);
    count(tag, "unchanged", unchanged);
  }

  private void count(String kind, String outcome, int amount) {
    registry.counter("ingest_items_total", "kind", kind, "outcome", outcome).increment(amount);
  }
}
