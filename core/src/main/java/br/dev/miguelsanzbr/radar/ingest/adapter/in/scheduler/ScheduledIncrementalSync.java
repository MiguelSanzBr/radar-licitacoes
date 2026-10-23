package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps each configured modality in sync, one after the other, while the {@link
 * Flag#INCREMENTAL_SYNC} flag is on. The flag is read at each tick, so turning it off takes effect
 * before the next run.
 */
@Component
class ScheduledIncrementalSync {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledIncrementalSync.class);

  private final IncrementalSyncUseCase useCase;
  private final IngestScheduleProperties properties;
  private final Flags flags;

  ScheduledIncrementalSync(
      IncrementalSyncUseCase useCase, IngestScheduleProperties properties, Flags flags) {
    this.useCase = useCase;
    this.properties = properties;
    this.flags = flags;
  }

  @Scheduled(fixedDelayString = "${radar.ingest.incremental.interval:5m}")
  void run() {
    if (!flags.isActive(Flag.INCREMENTAL_SYNC)) {
      return;
    }
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
