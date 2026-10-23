package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.PartitionMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the partitions of the current and the next months once a day (UTC) and creates them again
 * at startup, then checks them. The gauge {@code ingest_partitions_ahead} and the alert {@code
 * ParticaoFutura} watch the same state.
 */
@Component
@ConditionalOnProperty(name = "radar.ingest.partitions.enabled", havingValue = "true")
class ScheduledPartitionMaintenance {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledPartitionMaintenance.class);

  private final PartitionMaintenance maintenance;

  ScheduledPartitionMaintenance(PartitionMaintenance maintenance) {
    this.maintenance = maintenance;
  }

  @Scheduled(cron = "${radar.ingest.partitions.cron:0 15 3 * * *}", zone = "UTC")
  void run() {
    try {
      maintenance.ensure();
      LOG.info("partitions ensured, {} months ahead", maintenance.monthsAhead());
    } catch (RuntimeException e) {
      LOG.error(
          "partition maintenance failed, the next run retries (docs/runbook.md#particao-ausente)",
          e);
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void checkOnStartup() {
    try {
      // a stopped job (or a long stop of the app) is repaired at once, not at the next 03:15
      maintenance.ensure();
      var ahead = maintenance.monthsAhead();
      if (ahead < 1) {
        LOG.error(
            "the partition of the current or the next month of ingest.procurement does not exist:"
                + " inserts of notices published then will fail; run ingest.ensure_partition"
                + " (docs/runbook.md#particao-ausente)");
      } else {
        LOG.info("partition check: {} months ahead", ahead);
      }
    } catch (RuntimeException e) {
      LOG.error("partition check failed (docs/runbook.md#particao-ausente)", e);
    }
  }
}
