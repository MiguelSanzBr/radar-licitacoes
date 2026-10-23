package br.dev.miguelsanzbr.radar.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Relay and cleanup tuning. {@code radar.outbox.relay-enabled} and {@code
 * radar.outbox.poll-interval} are read by the scheduler directly. {@code mode} says who publishes:
 * the polling relay of this app, or Debezium (CDC) reading the table from the WAL through the slot
 * {@code slotName}; the two never run together.
 */
@ConfigurationProperties("radar.outbox")
public record OutboxProperties(
    @DefaultValue("100") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("7d") Duration retention,
    @DefaultValue("10000") int cleanupBatchSize,
    @DefaultValue("polling") Mode mode,
    @DefaultValue("radar_outbox") String slotName) {

  public enum Mode {
    POLLING,
    CDC
  }
}
