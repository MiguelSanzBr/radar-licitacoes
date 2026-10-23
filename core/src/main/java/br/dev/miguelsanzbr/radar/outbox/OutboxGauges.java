package br.dev.miguelsanzbr.radar.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Backlog gauges read on each scrape; rows at max attempts count as poison, not as backlog age.
 * They describe the polling relay: in CDC mode nobody sets {@code published_at}, so they stay at
 * zero and the backlog is the lag of the replication slot ({@link ReplicationSlotGauge}).
 */
@Component
class OutboxGauges {

  OutboxGauges(JdbcTemplate jdbc, MeterRegistry registry, OutboxProperties properties) {
    var polling = properties.mode() == OutboxProperties.Mode.POLLING;
    Gauge.builder(
            "outbox_pending",
            whenPolling(
                polling,
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL",
                        Long.class)))
        .register(registry);
    Gauge.builder(
            "outbox_oldest_age_seconds",
            whenPolling(
                polling,
                () ->
                    jdbc.queryForObject(
                        """
                    SELECT coalesce(extract(epoch FROM now() - (
                      SELECT created_at FROM outbox.outbox_event
                      WHERE published_at IS NULL AND attempts < ? ORDER BY id LIMIT 1)), 0)
                    """,
                        Double.class,
                        properties.maxAttempts())))
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(
            "outbox_poison_pending",
            whenPolling(
                polling,
                () ->
                    jdbc.queryForObject(
                        "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL AND attempts >= ?",
                        Long.class,
                        properties.maxAttempts())))
        .register(registry);
  }

  /** In CDC mode the backlog is not in the table, so the gauge reads zero without a query. */
  private static Supplier<Number> whenPolling(boolean polling, Supplier<Number> query) {
    return () -> polling ? query.get() : 0;
  }
}
