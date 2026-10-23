package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncFailedException;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpBlockedException;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps each configured modality in sync, one after the other, while the {@link
 * Flag#INCREMENTAL_SYNC} flag is on. The flag is read at each tick, so turning it off takes effect
 * before the next run. When the PNCP has blocked the client the whole cycle is skipped.
 */
@Component
class ScheduledIncrementalSync {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledIncrementalSync.class);

  private final IncrementalSyncUseCase useCase;
  private final IngestScheduleProperties properties;
  private final Flags flags;
  private Instant announced;

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
    var blockedUntil = useCase.blockedUntil();
    if (blockedUntil.isPresent()) {
      // no run is started: it would only be saved as a failed one at every tick
      announce(blockedUntil.get());
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
      } catch (IncrementalSyncFailedException e) {
        if (e.getCause() instanceof PncpBlockedException blocked) {
          // the other modalities would only fail the same way, each one leaving a failed run
          announce(blocked.until());
          return;
        }
        LOG.warn("incremental sync of modality {} failed, the next tick retries", modality, e);
      } catch (RuntimeException e) {
        LOG.warn("incremental sync of modality {} failed, the next tick retries", modality, e);
      }
    }
  }

  /** One WARN per block, not one per tick. */
  private void announce(Instant until) {
    if (!until.equals(announced)) {
      announced = until;
      LOG.warn(
          "incremental sync skipped: PNCP blocked the client until {} (docs/runbook.md#pncp-bloqueado)",
          until);
    }
  }
}
