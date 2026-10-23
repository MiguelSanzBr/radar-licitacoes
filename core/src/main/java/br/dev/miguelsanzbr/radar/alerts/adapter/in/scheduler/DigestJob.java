package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily digest at the configured time of Sao Paulo. A run that dies half way leaves the rest
 * unsent, and the next run sends them.
 */
@Component
@ConditionalOnProperty(
    name = "radar.alerts.digest.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DigestJob {

  private static final Logger LOG = LoggerFactory.getLogger(DigestJob.class);

  private final SendDigestUseCase useCase;

  DigestJob(SendDigestUseCase useCase) {
    this.useCase = useCase;
  }

  @Scheduled(cron = "${radar.alerts.digest.cron:0 0 7 * * *}", zone = "America/Sao_Paulo")
  public void send() {
    var result = useCase.run();
    LOG.info(
        "alerts digest: {} e-mails with {} alerts, {} subscribers left for the next run",
        result.emails(),
        result.alerts(),
        result.failed());
  }
}
