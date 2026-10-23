package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.EstimatedValueCentsBackfill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the cents backfill while {@code radar.ingest.value-cents-backfill.enabled=true} (off by
 * default: it is the second step of the release sequence in ADR-0010). A run with nothing pending
 * costs one count, so the job can stay on until the contract release.
 */
@Component
@ConditionalOnProperty(name = "radar.ingest.value-cents-backfill.enabled", havingValue = "true")
class ScheduledEstimatedValueCentsBackfill {

  private static final Logger LOG =
      LoggerFactory.getLogger(ScheduledEstimatedValueCentsBackfill.class);

  private final EstimatedValueCentsBackfill backfill;

  ScheduledEstimatedValueCentsBackfill(EstimatedValueCentsBackfill backfill) {
    this.backfill = backfill;
  }

  @Scheduled(fixedDelayString = "${radar.ingest.value-cents-backfill.interval:10m}")
  void run() {
    try {
      var filled = backfill.run();
      if (filled > 0) {
        LOG.info("value cents backfill filled {} rows", filled);
      }
    } catch (RuntimeException e) {
      LOG.warn("value cents backfill failed, the next tick retries", e);
    }
  }
}
