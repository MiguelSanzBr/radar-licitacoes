package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class BackfillPropertiesTest {

  private static BackfillProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("radar.ingest.backfill", BackfillProperties.class);
  }

  @Test
  void defaultsAreTheNightWindowWithTheScheduleOff() {
    var properties = bind(Map.of());

    assertThat(properties.allowedFrom()).isEqualTo(LocalTime.of(0, 0));
    assertThat(properties.allowedTo()).isEqualTo(LocalTime.of(6, 0));
    assertThat(properties.scheduledEnabled()).isFalse();
    assertThat(properties.scheduledFrom()).isNull();
    assertThat(properties.scheduledModality()).isEqualTo(6);
  }

  @Test
  void everythingCanBeOverridden() {
    var properties =
        bind(
            Map.of(
                "radar.ingest.backfill.allowed-from", "22:00",
                "radar.ingest.backfill.allowed-to", "06:30",
                "radar.ingest.backfill.scheduled-enabled", "true",
                "radar.ingest.backfill.scheduled-from", "2026-01-01",
                "radar.ingest.backfill.scheduled-modality", "12"));

    assertThat(properties.allowedFrom()).isEqualTo(LocalTime.of(22, 0));
    assertThat(properties.allowedTo()).isEqualTo(LocalTime.of(6, 30));
    assertThat(properties.scheduledEnabled()).isTrue();
    assertThat(properties.scheduledFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(properties.scheduledModality()).isEqualTo(12);
  }

  @Test
  void aScheduleWithoutAStartDayIsRejected() {
    assertThatThrownBy(() -> bind(Map.of("radar.ingest.backfill.scheduled-enabled", "true")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }
}
