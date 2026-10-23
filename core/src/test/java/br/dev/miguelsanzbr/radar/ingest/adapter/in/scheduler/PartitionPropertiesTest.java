package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PartitionPropertiesTest {

  private static PartitionProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("radar.ingest.partitions", PartitionProperties.class);
  }

  @Test
  void theDefaultHorizonIsThreeMonths() {
    assertThat(bind(Map.of()).monthsAhead()).isEqualTo(3);
  }

  @Test
  void theHorizonCanBeOverridden() {
    assertThat(bind(Map.of("radar.ingest.partitions.months-ahead", "6")).monthsAhead())
        .isEqualTo(6);
  }
}
