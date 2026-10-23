package br.dev.miguelsanzbr.radar.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CDC mode only: how many bytes of WAL the Debezium slot has yet to confirm, and whether anyone
 * reads it (1 or 0); both are -1 when the slot does not exist. An inactive or slow slot keeps the
 * WAL on disk, so these are the alert signals.
 */
@Component
@ConditionalOnProperty(name = "radar.outbox.mode", havingValue = "cdc")
class ReplicationSlotGauge {

  ReplicationSlotGauge(JdbcTemplate jdbc, MeterRegistry registry, OutboxProperties properties) {
    Gauge.builder(
            "outbox_replication_slot_lag_bytes",
            () ->
                jdbc.queryForObject(
                    """
                    SELECT coalesce((
                      SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)
                      FROM pg_replication_slots WHERE slot_name = ?), -1)
                    """,
                    Double.class,
                    properties.slotName()))
        .baseUnit("bytes")
        .register(registry);
    Gauge.builder(
            "outbox_replication_slot_active",
            () ->
                jdbc.queryForObject(
                    """
                    SELECT coalesce((
                      SELECT active::int FROM pg_replication_slots WHERE slot_name = ?), -1)
                    """,
                    Integer.class,
                    properties.slotName()))
        .register(registry);
  }
}
