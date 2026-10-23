package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily digest at the configured time of Sao Paulo, while the {@link Flag#ALERT_DIGEST} flag is on.
 * A run that dies half way leaves the rest unsent, and the next run sends them.
 */
@Component
public class DigestJob {

  private static final Logger LOG = LoggerFactory.getLogger(DigestJob.class);

  private final SendDigestUseCase useCase;
  private final Flags flags;

  DigestJob(SendDigestUseCase useCase, Flags flags) {
    this.useCase = useCase;
    this.flags = flags;
  }

  @Scheduled(cron = "${radar.alerts.digest.cron:0 0 7 * * *}", zone = "America/Sao_Paulo")
  public void send() {
    if (!flags.isActive(Flag.ALERT_DIGEST)) {
      return;
    }
    var result = useCase.run();
    LOG.info(
        "alerts digest: {} e-mails with {} alerts, {} subscribers left for the next run",
        result.emails(),
        result.alerts(),
        result.failed());
  }
}
