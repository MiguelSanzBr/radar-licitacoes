package br.dev.miguelsanzbr.radar.alerts.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.RecordingNotifierMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.MailDeliveryException;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics.Kind;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpMailerTest {

  private static final AlertMatched ALERT =
      AlertMatched.of(
          new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), Frequency.INSTANT),
          Facts.procurement().build());

  private final List<MimeMessage> sent = new ArrayList<>();
  private boolean refuse;
  private final RecordingNotifierMetrics metrics = new RecordingNotifierMetrics();

  private final JavaMailSenderImpl sender =
      new JavaMailSenderImpl() {
        @Override
        public void send(MimeMessage message) {
          if (refuse) {
            throw new MailSendException("connection refused");
          }
          sent.add(message);
        }
      };

  private final SmtpMailer mailer =
      new SmtpMailer(
          sender, new MailTemplates("https://radar.example.org"), "radar@localhost", metrics);

  @Test
  void anInstantAlertGoesFromTheConfiguredSenderToTheSubscriber() throws Exception {
    mailer.sendInstant("fulano@example.com", "Notebooks", ALERT);

    var message = sent.getFirst();
    assertThat(message.getFrom()[0].toString()).isEqualTo("radar@localhost");
    assertThat(message.getRecipients(RecipientType.TO)[0].toString())
        .isEqualTo("fulano@example.com");
    assertThat(message.getSubject()).contains("Notebooks");
    assertThat(message.getContent()).isInstanceOf(Multipart.class);
  }

  @Test
  void aDigestGoesToTheSubscriberWithOneMessageForAllItems() throws Exception {
    var items = List.of(new Queued(UUID.randomUUID(), "fulano@example.com", "Notebooks", ALERT));

    mailer.sendDigest("fulano@example.com", items);

    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().getSubject()).isEqualTo("Resumo diário: 1 nova licitação");
  }

  @Test
  void theVerificationLinkGoesToTheAddressThatRegistered() throws Exception {
    mailer.send("fulano@example.com", "token-123");

    assertThat(sent.getFirst().getRecipients(RecipientType.TO)[0].toString())
        .isEqualTo("fulano@example.com");
    assertThat(sent.getFirst().getSubject()).startsWith("Confirme");
  }

  @Test
  void aRefusedConnectionSurfacesAsAMailDeliveryFailure() {
    refuse = true;

    assertThatThrownBy(() -> mailer.sendInstant("fulano@example.com", "Notebooks", ALERT))
        .isInstanceOf(MailDeliveryException.class)
        .hasCauseInstanceOf(MailSendException.class);
  }

  @Test
  void anAddressThatCannotBeParsedSurfacesAsAMailDeliveryFailure() {
    assertThatThrownBy(() -> mailer.sendInstant("not an address", "Notebooks", ALERT))
        .isInstanceOf(MailDeliveryException.class)
        .hasCauseInstanceOf(MessagingException.class);
  }

  @Test
  void everyRefusalIsCountedUnderTheKindOfTheMessage() {
    refuse = true;
    var items = List.of(new Queued(UUID.randomUUID(), "fulano@example.com", "Notebooks", ALERT));

    assertThatThrownBy(() -> mailer.sendInstant("fulano@example.com", "Notebooks", ALERT))
        .isInstanceOf(MailDeliveryException.class);
    assertThatThrownBy(() -> mailer.sendDigest("fulano@example.com", items))
        .isInstanceOf(MailDeliveryException.class);
    assertThatThrownBy(() -> mailer.send("fulano@example.com", "token"))
        .isInstanceOf(MailDeliveryException.class);
    assertThatThrownBy(() -> mailer.sendInstant("not an address", "Notebooks", ALERT))
        .isInstanceOf(MailDeliveryException.class);

    assertThat(metrics.failures)
        .containsExactly(Kind.INSTANT, Kind.DIGEST, Kind.VERIFICATION, Kind.INSTANT);
  }

  @Test
  void aSentMessageIsNotCountedAsAFailure() {
    mailer.sendInstant("fulano@example.com", "Notebooks", ALERT);

    assertThat(metrics.failures).isEmpty();
  }
}
