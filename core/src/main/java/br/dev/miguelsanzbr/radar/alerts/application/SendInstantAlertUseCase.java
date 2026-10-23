package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.AlertMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.port.ProcessedEvents;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Sends the e-mail of an INSTANT alert. The delivery row is the dedup: a row with a sent time is
 * never mailed again, so a redelivered event sends nothing. The mail goes out first and the row is
 * marked after the server accepted it. If the mark fails after the send, the redelivery sends a
 * second copy (at-least-once); marking first would instead lose the mail when the process dies
 * between the two steps (ADR-0016).
 */
public class SendInstantAlertUseCase {

  public static final String CONSUMER = "alerts-notifier";

  private final DeliveryLog log;
  private final ProcessedEvents inbox;
  private final AlertMailer mailer;
  private final NotifierMetrics metrics;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public SendInstantAlertUseCase(
      DeliveryLog log,
      ProcessedEvents inbox,
      AlertMailer mailer,
      NotifierMetrics metrics,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.log = log;
    this.inbox = inbox;
    this.mailer = mailer;
    this.metrics = metrics;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** True when a mail was sent; a mail failure propagates so the listener retries. */
  public boolean send(UUID eventId, AlertMatched alert) {
    var delivery = log.find(alert.savedSearchId(), alert.procurementId());
    if (delivery.isEmpty() || delivery.get().sentAt() != null) {
      return false;
    }
    mailer.sendInstant(delivery.get().email(), delivery.get().searchName(), alert);
    var now = clock.instant();
    unitOfWork.run(
        () -> {
          log.markSent(alert.savedSearchId(), alert.procurementId(), now);
          inbox.firstTime(CONSUMER, eventId, now);
        });
    metrics.delivered(Duration.between(delivery.get().matchedAt(), now));
    return true;
  }
}
