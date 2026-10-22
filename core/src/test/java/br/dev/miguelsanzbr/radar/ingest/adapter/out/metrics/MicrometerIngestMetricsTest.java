package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerIngestMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerIngestMetrics metrics = new MicrometerIngestMetrics(registry);

  private double count(String kind, String outcome) {
    return registry.counter("ingest_items_total", "kind", kind, "outcome", outcome).count();
  }

  @Test
  void countsEachOutcomeUnderTheLowerCaseKind() {
    metrics.itemsProcessed(IngestionKind.INCREMENTAL, 3, 2, 1);
    metrics.itemsProcessed(IngestionKind.INCREMENTAL, 1, 0, 4);
    metrics.itemsProcessed(IngestionKind.BACKFILL, 7, 0, 0);

    assertThat(count("incremental", "discovered")).isEqualTo(4);
    assertThat(count("incremental", "updated")).isEqualTo(2);
    assertThat(count("incremental", "unchanged")).isEqualTo(5);
    assertThat(count("backfill", "discovered")).isEqualTo(7);
    assertThat(count("backfill", "updated")).isZero();
  }
}
