package br.dev.miguelsanzbr.radar.alerts.adapter.out.mail;

import br.dev.miguelsanzbr.radar.alerts.application.MailDeliveryException;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics.Kind;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationMailer;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import jakarta.mail.MessagingException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Every e-mail of the product goes through here, to the SMTP server of the environment. */
@Component
class SmtpMailer implements AlertMailer, VerificationMailer {

  private final JavaMailSender sender;
  private final MailTemplates templates;
  private final String from;
  private final NotifierMetrics metrics;

  SmtpMailer(
      JavaMailSender sender,
      MailTemplates templates,
      @Value("${radar.mail.from}") String from,
      NotifierMetrics metrics) {
    this.sender = sender;
    this.templates = templates;
    this.from = from;
    this.metrics = metrics;
  }

  @Override
  public void sendInstant(String to, String searchName, AlertMatched alert) {
    deliver(Kind.INSTANT, to, templates.alert(searchName, alert));
  }

  @Override
  public void sendDigest(String to, List<Queued> items) {
    deliver(Kind.DIGEST, to, templates.digest(items));
  }

  /** Never logs the token. */
  @Override
  public void send(String email, String rawToken) {
    deliver(Kind.VERIFICATION, email, templates.verify(rawToken));
  }

  private void deliver(Kind kind, String to, MailTemplates.Mail mail) {
    try {
      var message = sender.createMimeMessage();
      var helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from);
      helper.setTo(to);
      helper.setSubject(mail.subject());
      helper.setText(mail.text(), mail.html());
      sender.send(message);
    } catch (MessagingException | MailException e) {
      metrics.mailFailed(kind);
      throw new MailDeliveryException(e);
    }
  }
}
