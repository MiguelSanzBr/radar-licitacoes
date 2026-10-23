package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.AlertMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * The daily digest: one e-mail per subscriber with everything queued for them, then all those rows
 * marked sent in one transaction. A subscriber whose mail the server refuses keeps the rows queued
 * for the next run and does not hold back the others.
 */
public class SendDigestUseCase {

  private static final System.Logger LOG = System.getLogger(SendDigestUseCase.class.getName());

  /** Mails sent, alerts they carried, subscribers left for the next run. */
  public record Result(int emails, int alerts, int failed) {}

  private final DeliveryLog log;
  private final AlertMailer mailer;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public SendDigestUseCase(
      DeliveryLog log, AlertMailer mailer, UnitOfWork unitOfWork, Clock clock) {
    this.log = log;
    this.mailer = mailer;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Result run() {
    var bySubscriber = new LinkedHashMap<UUID, List<Queued>>();
    for (var queued : log.unsentDigest()) {
      bySubscriber.computeIfAbsent(queued.subscriberId(), id -> new ArrayList<>()).add(queued);
    }
    int emails = 0;
    int alerts = 0;
    int failed = 0;
    for (var items : bySubscriber.values()) {
      try {
        mailer.sendDigest(items.getFirst().email(), items);
      } catch (MailDeliveryException e) {
        LOG.log(System.Logger.Level.WARNING, "digest kept for the next run", e);
        failed++;
        continue;
      }
      var now = clock.instant();
      unitOfWork.run(
          () ->
              items.forEach(
                  item ->
                      log.markSent(
                          item.alert().savedSearchId(), item.alert().procurementId(), now)));
      emails++;
      alerts += items.size();
    }
    return new Result(emails, alerts, failed);
  }
}
