package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillFailedException;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowBusyException;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpBlockedException;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Catch-up from the configured day until yesterday, one day at a time, inside the allowed window:
 * days that already have a finished run are skipped and the loop stops when the window closes.
 *
 * <p>The trigger is {@code radar.ingest.backfill.scheduled-cron} (default 00:05, five minutes into
 * the default 00:00 to 06:00 window). It is not derived from {@code allowed-from}: when the window
 * moves, move the cron with it, or the window check will skip every run.
 */
@Component
class ScheduledBackfill {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledBackfill.class);
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  private final BackfillUseCase useCase;
  private final BackfillWindowPolicy policy;
  private final BackfillProperties properties;
  private final Clock clock;
  private final Flags flags;
  private Instant announced;

  ScheduledBackfill(
      BackfillUseCase useCase,
      BackfillWindowPolicy policy,
      BackfillProperties properties,
      Clock clock,
      Flags flags) {
    this.useCase = useCase;
    this.policy = policy;
    this.properties = properties;
    this.clock = clock;
    this.flags = flags;
  }

  @Scheduled(
      cron = "${radar.ingest.backfill.scheduled-cron:0 5 0 * * *}",
      zone = "America/Sao_Paulo")
  void run() {
    if (!flags.isActive(Flag.BACKFILL_SCHEDULED)) {
      return;
    }
    // the flag can be switched on later without the starting day the property check asks for
    if (properties.scheduledFrom() == null) {
      LOG.warn(
          "scheduled backfill is on but radar.ingest.backfill.scheduled-from is not set, nothing runs");
      return;
    }
    var blockedUntil = useCase.blockedUntil();
    if (blockedUntil.isPresent()) {
      // no run is started: it would only be saved as a failed one
      announce(blockedUntil.get());
      return;
    }
    var yesterday = LocalDate.now(clock.withZone(ZONE)).minusDays(1);
    var modality = properties.scheduledModality();
    for (var day = properties.scheduledFrom(); !day.isAfter(yesterday); day = day.plusDays(1)) {
      if (!policy.allowed(clock.instant())) {
        return;
      }
      if (useCase.isDone(day, modality)) {
        continue;
      }
      try {
        useCase.run(day, day, modality);
      } catch (BackfillWindowBusyException e) {
        LOG.info("scheduled backfill skips {}: {}", day, e.getMessage());
      } catch (BackfillFailedException e) {
        if (e.getCause() instanceof PncpBlockedException blocked) {
          announce(blocked.until());
          return;
        }
        throw e;
      }
    }
  }

  /** One WARN per block, not one per cron tick. */
  private void announce(Instant until) {
    if (!until.equals(announced)) {
      announced = until;
      LOG.warn(
          "scheduled backfill skipped: PNCP blocked the client until {} (docs/runbook.md#pncp-bloqueado)",
          until);
    }
  }
}
