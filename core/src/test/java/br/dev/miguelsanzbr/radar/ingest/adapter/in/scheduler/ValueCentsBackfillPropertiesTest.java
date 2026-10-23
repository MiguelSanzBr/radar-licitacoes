package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ValueCentsBackfillPropertiesTest {

  private static ValueCentsBackfillProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("radar.ingest.value-cents-backfill", ValueCentsBackfillProperties.class);
  }

  @Test
  void theDefaultBatchHasFiveThousandRows() {
    assertThat(bind(Map.of()).batchSize()).isEqualTo(5_000);
  }

  @Test
  void theBatchSizeCanBeOverridden() {
    assertThat(bind(Map.of("radar.ingest.value-cents-backfill.batch-size", "250")).batchSize())
        .isEqualTo(250);
  }
}
