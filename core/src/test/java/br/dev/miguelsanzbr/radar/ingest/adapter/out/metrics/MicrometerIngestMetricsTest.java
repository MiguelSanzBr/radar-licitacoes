package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MicrometerIngestMetricsTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

  private final MutableClock clock = new MutableClock(NOW);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerIngestMetrics metrics = new MicrometerIngestMetrics(registry, clock);

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

  private double blockedUntil() {
    return registry.get("pncp_blocked_until_seconds").gauge().value();
  }

  @Test
  void blocksAreCountedByStatusAndTheGaugeIsZeroUntilOneHappens() {
    assertThat(blockedUntil()).isZero();

    metrics.pncpBlocked(429, NOW.plusSeconds(1800));
    metrics.pncpBlocked(429, NOW.plusSeconds(1800));
    metrics.pncpBlocked(403, NOW.plusSeconds(1800));

    assertThat(registry.counter("pncp_blocked_total", "status", "429").count()).isEqualTo(2);
    assertThat(registry.counter("pncp_blocked_total", "status", "403").count()).isEqualTo(1);
  }

  @Test
  void theGaugeHoldsTheEndOfTheBlockAndReturnsToZeroWhenItEnds() {
    var until = NOW.plusSeconds(1800);

    metrics.pncpBlocked(429, until);

    assertThat(blockedUntil()).isEqualTo(until.getEpochSecond());
    clock.advance(Duration.ofSeconds(1799));
    assertThat(blockedUntil()).isEqualTo(until.getEpochSecond());
    clock.advance(Duration.ofSeconds(1));
    assertThat(blockedUntil()).isZero();
  }

  @Test
  void theGaugeNeverMovesToAnEarlierEnd() {
    var later = NOW.plusSeconds(3600);

    metrics.pncpBlocked(429, later);
    metrics.pncpBlocked(429, NOW.plusSeconds(60));

    assertThat(blockedUntil()).isEqualTo(later.getEpochSecond());
    metrics.pncpBlocked(403, later.plusSeconds(60));
    assertThat(blockedUntil()).isEqualTo(later.plusSeconds(60).getEpochSecond());
  }
}
