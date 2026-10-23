package br.dev.miguelsanzbr.radar.alerts.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.SharedMailpit;
import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.outbox.OutboxTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The confirmation link that Spring Mail really sends to Mailpit works against the page. */
@SpringBootTest(
    properties = {
      "radar.outbox.relay-enabled=false",
      "radar.outbox.publisher=inmemory",
      "radar.public-base-url=https://radar.example.org"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfig.class, OutboxTestConfig.class})
class VerificationEmailIT {

  private static final Pattern LINK =
      Pattern.compile("https://radar\\.example\\.org(/assinatura/confirmar\\?token=[\\w-]+)");

  @DynamicPropertySource
  static void mailpit(DynamicPropertyRegistry registry) {
    SharedMailpit.register(registry);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired JavaMailSenderImpl sender;
  @Autowired MeterRegistry registry;

  @BeforeEach
  void cleanSlate() {
    jdbc.sql(
            "TRUNCATE alerts.alert_delivery, alerts.saved_search, alerts.verification_token,"
                + " alerts.idempotency_key, alerts.subscriber, alerts.processed_event")
        .update();
    SharedMailpit.clear();
  }

  @Test
  void theMailedLinkConfirmsTheSubscriber() throws Exception {
    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("fulano-sub", "fulano@example.com"))
                .with(csrf())
                .param("consent", "on"))
        .andExpect(status().isAccepted());

    var mails = SharedMailpit.messagesTo("fulano@example.com");
    assertThat(mails).hasSize(1);
    assertThat(mails.getFirst().subject()).isEqualTo("Confirme seu e-mail no Radar de Licitações");
    var matcher = LINK.matcher(mails.getFirst().text());
    assertThat(matcher.find()).as("link in the text body").isTrue();
    assertThat(LINK.matcher(mails.getFirst().html()).find()).as("link in the html body").isTrue();

    mvc.perform(get(URI.create(matcher.group(1))))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("E-mail confirmado")));
    assertThat(
            jdbc.sql("SELECT verified_at IS NOT NULL FROM alerts.subscriber")
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void aMailServerThatIsDownAnswers503KeepsTheRegistrationAndALaterRetryResends() throws Exception {
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var workingPort = sender.getPort();
    var failuresBefore = failures();
    sender.setPort(closedPort);
    try {
      mvc.perform(subscribe())
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.type").value(endsWith("/problems/mail-unavailable.md")));
    } finally {
      sender.setPort(workingPort);
    }

    assertThat(failures() - failuresBefore).isEqualTo(1);
    assertThat(SharedMailpit.messagesTo("fulano@example.com")).isEmpty();
    assertThat(jdbc.sql("SELECT count(*) FROM alerts.subscriber").query(Long.class).single())
        .as("the registration stays committed")
        .isEqualTo(1);

    mvc.perform(subscribe()).andExpect(status().isAccepted());

    var mails = SharedMailpit.messagesTo("fulano@example.com");
    assertThat(mails).hasSize(1);
    var link = LINK.matcher(mails.getFirst().text());
    assertThat(link.find()).isTrue();
    mvc.perform(get(URI.create(link.group(1)))).andExpect(status().isOk());
  }

  private double failures() {
    return registry.get("alert_mail_failures_total").tag("kind", "verification").counter().count();
  }

  private static MockHttpServletRequestBuilder subscribe() {
    return post("/assinatura")
        .with(Auth.webSupplier("fulano-sub", "fulano@example.com"))
        .with(csrf())
        .param("consent", "on");
  }
}
