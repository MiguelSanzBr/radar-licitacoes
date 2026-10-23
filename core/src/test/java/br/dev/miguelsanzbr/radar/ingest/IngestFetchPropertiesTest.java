package br.dev.miguelsanzbr.radar.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class IngestFetchPropertiesTest {

  private static IngestFetchProperties bind(Map<String, String> source) {
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate("radar.ingest", IngestFetchProperties.class);
  }

  @Test
  void twoPagesAheadByDefaultSoTheRateLimiterQueueStaysShort() {
    assertThat(bind(Map.of()).fetchAhead()).isEqualTo(2);
  }

  @Test
  void canBeOverridden() {
    assertThat(bind(Map.of("radar.ingest.fetch-ahead", "4")).fetchAhead()).isEqualTo(4);
  }

  @Test
  void zeroIsRefused() {
    assertThatThrownBy(() -> bind(Map.of("radar.ingest.fetch-ahead", "0")))
        .hasRootCauseMessage("fetchAhead must be >= 1");
  }

  @Test
  void theReadNeverGoesAheadOfTheBulkhead() {
    assertThat(IngestFetchProperties.effective(8, 4)).isEqualTo(4);
    assertThat(IngestFetchProperties.effective(2, 4)).isEqualTo(2);
  }
}
