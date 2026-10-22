package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Closes the notices whose proposal window ended, every 10 minutes by default. */
@Component
@ConditionalOnProperty(name = "radar.ingest.close-expired.enabled", havingValue = "true")
class ScheduledCloseExpired {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledCloseExpired.class);

  private final CloseExpiredUseCase useCase;

  ScheduledCloseExpired(CloseExpiredUseCase useCase) {
    this.useCase = useCase;
  }

  @Scheduled(fixedDelayString = "${radar.ingest.close-expired.interval:10m}")
  void run() {
    try {
      var closed = useCase.closeExpired();
      LOG.info("close-expired closed {} notices", closed);
    } catch (RuntimeException e) {
      LOG.warn("close-expired failed, the next tick retries", e);
    }
  }
}
