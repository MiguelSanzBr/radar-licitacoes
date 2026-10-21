package br.dev.miguelsanzbr.radar.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Triggers the relay and the daily cleanup; off when {@code radar.outbox.relay-enabled=false}. */
@Component
@ConditionalOnProperty(
    name = "radar.outbox.relay-enabled",
    havingValue = "true",
    matchIfMissing = true)
class OutboxScheduler {

  private final OutboxRelay relay;
  private final OutboxCleanup cleanup;
  private final OutboxProperties properties;

  OutboxScheduler(OutboxRelay relay, OutboxCleanup cleanup, OutboxProperties properties) {
    this.relay = relay;
    this.cleanup = cleanup;
    this.properties = properties;
  }

  /** Keeps going while batches come back full, so a backlog drains without waiting for polls. */
  @Scheduled(fixedDelayString = "${radar.outbox.poll-interval:500ms}")
  void relay() {
    while (relay.relayOnce() == properties.batchSize()) {
      // drain
    }
  }

  @Scheduled(cron = "${radar.outbox.cleanup-cron:0 17 3 * * *}")
  void cleanup() {
    cleanup.cleanOnce();
  }
}
