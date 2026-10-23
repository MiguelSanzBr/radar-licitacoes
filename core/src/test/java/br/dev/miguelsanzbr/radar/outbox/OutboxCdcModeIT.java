package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code radar.outbox.mode=cdc}: Debezium publishes, so the app only appends and purges by age. */
@SpringBootTest(properties = {"radar.outbox.mode=cdc", "radar.outbox.publisher=inmemory"})
@Import({PostgresTestConfig.class, OutboxTestConfig.class})
class OutboxCdcModeIT {

  private static final String SLOT = "radar_outbox";

  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry registry;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  @Test
  void theRelayBeanIsOutButTheSchedulerStaysForTheCleanup() {
    assertThat(context.getBeansOfType(OutboxRelay.class)).isEmpty();
    assertThat(context.getBeansOfType(OutboxScheduler.class)).hasSize(1);
    assertThat(context.getBean(OutboxProperties.class).mode()).isEqualTo(OutboxProperties.Mode.CDC);
  }

  @Test
  void backlogGaugesStayAtZeroBecausePublishedAtIsNeverSet() {
    var id = OutboxRows.insert(jdbc, "A", "ProcurementUpdated");
    jdbc.update(
        "UPDATE outbox.outbox_event SET created_at = now() - interval '1 day' WHERE id = ?", id);

    assertThat(gauge("outbox_pending")).isZero();
    assertThat(gauge("outbox_oldest_age_seconds")).isZero();
    assertThat(gauge("outbox_poison_pending")).isZero();
  }

  @Test
  void slotLagGaugeIsMinusOneWithoutSlotAndTheLagInBytesWithOne() {
    assertThat(gauge("outbox_replication_slot_lag_bytes")).isEqualTo(-1);

    jdbc.queryForList("SELECT pg_create_logical_replication_slot(?, 'pgoutput')", SLOT);
    try {
      OutboxRows.insert(jdbc, "A", "ProcurementUpdated");

      assertThat(gauge("outbox_replication_slot_lag_bytes")).isPositive();
    } finally {
      jdbc.queryForList("SELECT pg_drop_replication_slot(?)", SLOT);
    }
  }

  @Test
  void slotActiveGaugeIsMinusOneWithoutSlotAndZeroForASlotNobodyReads() {
    assertThat(gauge("outbox_replication_slot_active")).isEqualTo(-1);

    jdbc.queryForList("SELECT pg_create_logical_replication_slot(?, 'pgoutput')", SLOT);
    try {
      assertThat(gauge("outbox_replication_slot_active")).isZero();
    } finally {
      jdbc.queryForList("SELECT pg_drop_replication_slot(?)", SLOT);
    }
  }
}
