package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.alerts.application.PurgeExpiredUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily retention job. Idempotent deletes, so running it on several instances is harmless. */
@Component
public class PurgeJob {

  private static final Logger LOG = LoggerFactory.getLogger(PurgeJob.class);

  private final PurgeExpiredUseCase useCase;

  PurgeJob(PurgeExpiredUseCase useCase) {
    this.useCase = useCase;
  }

  @Scheduled(cron = "${radar.alerts.purge-cron:0 15 3 * * *}")
  public void purge() {
    var purged = useCase.run();
    LOG.info(
        "alerts purge: {} idempotency keys, {} unverified subscribers",
        purged.idempotencyKeys(),
        purged.subscribers());
  }
}
