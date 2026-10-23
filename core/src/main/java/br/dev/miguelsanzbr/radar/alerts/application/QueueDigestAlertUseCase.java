package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.application.port.ProcessedEvents;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.time.Clock;
import java.util.UUID;

/** Keeps a DAILY alert on its delivery row until the digest mails it. */
public class QueueDigestAlertUseCase {

  private final DeliveryLog log;
  private final ProcessedEvents inbox;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public QueueDigestAlertUseCase(
      DeliveryLog log, ProcessedEvents inbox, UnitOfWork unitOfWork, Clock clock) {
    this.log = log;
    this.inbox = inbox;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public void queue(UUID eventId, AlertMatched alert) {
    unitOfWork.run(
        () -> {
          if (inbox.firstTime(SendInstantAlertUseCase.CONSUMER, eventId, clock.instant())) {
            log.keepForDigest(alert);
          }
        });
  }
}
