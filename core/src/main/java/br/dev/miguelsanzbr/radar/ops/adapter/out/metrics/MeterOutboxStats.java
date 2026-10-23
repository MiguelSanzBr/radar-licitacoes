package br.dev.miguelsanzbr.radar.ops.adapter.out.metrics;

import br.dev.miguelsanzbr.radar.ops.application.port.OutboxStats;
import br.dev.miguelsanzbr.radar.ops.domain.OutboxBacklog;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The backlog as the outbox already measures it for Prometheus, so the panel and the alerts agree
 * and the ops context does not query the outbox tables.
 */
@Component
class MeterOutboxStats implements OutboxStats {

  private final MeterRegistry registry;

  MeterOutboxStats(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public OutboxBacklog read() {
    return new OutboxBacklog(
        (long) gauge("outbox_pending"),
        gauge("outbox_oldest_age_seconds"),
        (long) gauge("outbox_poison_pending"));
  }

  private double gauge(String name) {
    var gauge = registry.find(name).gauge();
    if (gauge == null) {
      throw new IllegalStateException("gauge " + name + " is not registered");
    }
    var value = gauge.value();
    if (Double.isNaN(value)) {
      throw new IllegalStateException("gauge " + name + " could not be read");
    }
    return value;
  }
}
