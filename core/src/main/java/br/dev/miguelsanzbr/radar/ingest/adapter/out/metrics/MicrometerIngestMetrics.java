package br.dev.miguelsanzbr.radar.ingest.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestMetrics;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * {@code ingest_items_total{kind, outcome}}: notices per kind of run and what became of them.
 * {@code pncp_blocked_total{status}} counts the runs that stopped on a PNCP block and {@code
 * pncp_blocked_until_seconds} is the end of the latest block as epoch seconds, 0 when none is
 * running (it never moves to an earlier end, and goes back to 0 by itself when the block ends).
 */
@Component
class MicrometerIngestMetrics implements IngestMetrics {

  private final MeterRegistry registry;
  private final Clock clock;
  private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.EPOCH);

  MicrometerIngestMetrics(MeterRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
    Gauge.builder("pncp_blocked_until_seconds", this, MicrometerIngestMetrics::blockedUntilSeconds)
        .register(registry);
  }

  @Override
  public void itemsProcessed(IngestionKind kind, int discovered, int updated, int unchanged) {
    var tag = kind.name().toLowerCase(Locale.ROOT);
    count(tag, "discovered", discovered);
    count(tag, "updated", updated);
    count(tag, "unchanged", unchanged);
  }

  @Override
  public void pncpBlocked(int status, Instant until) {
    registry.counter("pncp_blocked_total", "status", Integer.toString(status)).increment();
    blockedUntil.accumulateAndGet(until, (current, next) -> next.isAfter(current) ? next : current);
  }

  private double blockedUntilSeconds() {
    var until = blockedUntil.get();
    return clock.instant().isBefore(until) ? until.getEpochSecond() : 0;
  }

  private void count(String kind, String outcome, int amount) {
    registry.counter("ingest_items_total", "kind", kind, "outcome", outcome).increment(amount);
  }
}
