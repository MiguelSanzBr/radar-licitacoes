package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps each configured modality in sync, one after the other. The switch is a property until the
 * feature flags of PR 4.3 replace it.
 */
@Component
@ConditionalOnProperty(name = "radar.ingest.incremental.enabled", havingValue = "true")
class ScheduledIncrementalSync {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledIncrementalSync.class);

  private final IncrementalSyncUseCase useCase;
  private final IngestScheduleProperties properties;

  ScheduledIncrementalSync(IncrementalSyncUseCase useCase, IngestScheduleProperties properties) {
    this.useCase = useCase;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${radar.ingest.incremental.interval:5m}")
  void run() {
    for (var modality : properties.modalities()) {
      try {
        var run = useCase.sync(modality);
        LOG.info(
            "incremental sync of modality {}: {} fetched, {} discovered, {} updated, {} unchanged",
            modality,
            run.fetched(),
            run.discovered(),
            run.updated(),
            run.unchanged());
      } catch (RuntimeException e) {
        LOG.warn("incremental sync of modality {} failed, the next tick retries", modality, e);
      }
    }
  }
}
