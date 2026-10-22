package br.dev.miguelsanzbr.radar.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IngestionCursorTest {

  private static final Instant WATERMARK = Instant.parse("2026-10-22T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-22T10:07:00Z");
  private static final Duration OVERLAP = Duration.ofMinutes(15);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final IngestionCursor cursor =
      new IngestionCursor(6, WATERMARK, OVERLAP, Instant.parse("2026-10-22T09:00:00Z"));

  @Test
  void theNextWindowStartsOneOverlapBeforeTheWatermarkAndEndsNow() {
    var window = cursor.nextWindow(NOW);

    assertThat(window.start()).isEqualTo(Instant.parse("2026-10-22T09:45:00Z"));
    assertThat(window.end()).isEqualTo(NOW);
  }

  @Test
  void aFirstCursorLooksBackByTheInitialLookbackFromNow() {
    var first = IngestionCursor.initial(6, NOW, Duration.ofDays(1), OVERLAP);

    assertThat(first.modalityId()).isEqualTo(6);
    assertThat(first.watermark()).isEqualTo(Instant.parse("2026-10-21T10:07:00Z"));
    assertThat(first.overlap()).isEqualTo(OVERLAP);
    assertThat(first.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void advancingMovesTheWatermarkAndStampsTheUpdate() {
    var next = cursor.advance(Instant.parse("2026-10-22T10:05:00Z"), CLOCK);

    assertThat(next.watermark()).isEqualTo(Instant.parse("2026-10-22T10:05:00Z"));
    assertThat(next.updatedAt()).isEqualTo(NOW);
    assertThat(next.modalityId()).isEqualTo(6);
    assertThat(next.overlap()).isEqualTo(OVERLAP);
  }

  @Test
  void advancingToTheSameInstantIsAllowed() {
    assertThat(cursor.advance(WATERMARK, CLOCK).watermark()).isEqualTo(WATERMARK);
  }

  @Test
  void theWatermarkNeverMovesBackwards() {
    assertThatThrownBy(() -> cursor.advance(WATERMARK.minusSeconds(1), CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2026-10-22T09:59:59Z");
  }
}
