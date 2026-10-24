package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.ClusterLock;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily digest at the configured time of Sao Paulo, while the {@link Flag#ALERT_DIGEST} flag is on.
 * A run that dies half way leaves the rest unsent, and the next run sends them. The run holds a
 * cluster-wide lock while each subscriber is still sent and marked in its own short transaction:
 * with several instances only one sends and the others skip, so a subscriber gets a single digest.
 */
@Component
public class DigestJob {

  private static final Logger LOG = LoggerFactory.getLogger(DigestJob.class);

  private static final String LOCK = "radar.alerts.digest";

  private final SendDigestUseCase useCase;
  private final Flags flags;
  private final ClusterLock lock;

  DigestJob(SendDigestUseCase useCase, Flags flags, ClusterLock lock) {
    this.useCase = useCase;
    this.flags = flags;
    this.lock = lock;
  }

  @Scheduled(cron = "${radar.alerts.digest.cron:0 0 7 * * *}", zone = "America/Sao_Paulo")
  public void send() {
    if (!flags.isActive(Flag.ALERT_DIGEST)) {
      return;
    }
    if (!lock.tryRun(LOCK, this::run)) {
      LOG.debug("alerts digest: another instance is running it, skipped");
    }
  }

  private void run() {
    var result = useCase.run();
    LOG.info(
        "alerts digest: {} e-mails with {} alerts, {} subscribers left for the next run",
        result.emails(),
        result.alerts(),
        result.failed());
  }
}
