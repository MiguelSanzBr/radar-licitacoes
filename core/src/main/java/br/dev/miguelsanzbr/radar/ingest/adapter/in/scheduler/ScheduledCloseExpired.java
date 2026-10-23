package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes the notices whose proposal window ended, every 10 minutes by default, while the {@link
 * Flag#CLOSE_EXPIRED} flag is on.
 */
@Component
class ScheduledCloseExpired {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledCloseExpired.class);

  private final CloseExpiredUseCase useCase;
  private final Flags flags;

  ScheduledCloseExpired(CloseExpiredUseCase useCase, Flags flags) {
    this.useCase = useCase;
    this.flags = flags;
  }

  @Scheduled(fixedDelayString = "${radar.ingest.close-expired.interval:10m}")
  void run() {
    if (!flags.isActive(Flag.CLOSE_EXPIRED)) {
      return;
    }
    try {
      var closed = useCase.closeExpired();
      LOG.info("close-expired closed {} notices", closed);
    } catch (RuntimeException e) {
      LOG.warn("close-expired failed, the next tick retries", e);
    }
  }
}
