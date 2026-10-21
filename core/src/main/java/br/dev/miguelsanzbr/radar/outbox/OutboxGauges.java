package br.dev.miguelsanzbr.radar.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Backlog gauges read on each scrape; rows at max attempts count as poison, not as backlog age. */
@Component
class OutboxGauges {

  OutboxGauges(JdbcTemplate jdbc, MeterRegistry registry, OutboxProperties properties) {
    Gauge.builder(
            "outbox_pending",
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL",
                    Long.class))
        .register(registry);
    Gauge.builder(
            "outbox_oldest_age_seconds",
            () ->
                jdbc.queryForObject(
                    """
                    SELECT coalesce(extract(epoch FROM now() - (
                      SELECT created_at FROM outbox.outbox_event
                      WHERE published_at IS NULL AND attempts < ? ORDER BY id LIMIT 1)), 0)
                    """,
                    Double.class,
                    properties.maxAttempts()))
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(
            "outbox_poison_pending",
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL AND attempts >= ?",
                    Long.class,
                    properties.maxAttempts()))
        .register(registry);
  }
}
