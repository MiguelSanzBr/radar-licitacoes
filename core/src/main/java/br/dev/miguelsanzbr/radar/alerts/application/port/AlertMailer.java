package br.dev.miguelsanzbr.radar.alerts.application.port;

import br.dev.miguelsanzbr.radar.alerts.application.MailDeliveryException;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.util.List;

/** Sends alert e-mail; returns only after the mail server accepted the message. */
public interface AlertMailer {

  /**
   * @throws MailDeliveryException when the server does not accept the message
   */
  void sendInstant(String to, String searchName, AlertMatched alert);

  /** One message for all the items. */
  void sendDigest(String to, List<Queued> items);
}
