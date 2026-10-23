package br.dev.miguelsanzbr.radar.alerts.adapter;

import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.delivery;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.namedSearch;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

import br.dev.miguelsanzbr.radar.SharedMailpit;
import br.dev.miguelsanzbr.radar.alerts.AlertEnvelopes;
import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SendInstantAlertUseCase;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * AlertMatched events travel the real way (Kafka, the notifier listener, Spring Mail) into a real
 * Mailpit that the tests read through its REST API.
 */
@KafkaSpringBootTest
@TestPropertySource(
    properties = {
      "radar.alerts.notifier.enabled=true",
      "radar.alerts.notifier.retry-interval=20ms",
      "radar.alerts.notifier.max-retries=3",
      "radar.alerts.notifier.outage-retry-interval=50ms",
      "radar.alerts.max-event-age=P1D",
      "radar.public-base-url=https://radar.example.org"
    })
class EmailNotifierIT {

  private static final String TOPIC = "radar.alert.v1";
  private static final String P1 = "83021808000182-1-000001/2026";
  private static final String P2 = "83021808000182-1-000002/2026";
  private static final String P3 = "83021808000182-1-000003/2026";
  private static final String LINK1 =
      "https://radar.example.org/licitacoes/83021808000182-1-000001-2026";

  @DynamicPropertySource
  static void mailpit(DynamicPropertyRegistry registry) {
    SharedMailpit.register(registry);
  }

  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> template;
  @Autowired SendDigestUseCase digest;
  @Autowired JavaMailSenderImpl sender;
  @Autowired MeterRegistry registry;
  @MockitoSpyBean SendInstantAlertUseCase instant;

  @BeforeEach
  void cleanSlate() {
    jdbc.sql(
            "TRUNCATE alerts.alert_delivery, alerts.saved_search, alerts.verification_token,"
                + " alerts.idempotency_key, alerts.subscriber, alerts.processed_event")
        .update();
    SharedMailpit.clear();
  }

  private static void until(BooleanSupplier condition) {
    var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met in 60 s");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private static String addressOf(UUID subscriber) {
    return subscriber + "@matcher.test";
  }

  private Instant sentAt(UUID search, String procurement) {
    return jdbc.sql(
            "SELECT sent_at FROM alerts.alert_delivery"
                + " WHERE saved_search_id = :s AND procurement_id = :p")
        .param("s", search)
        .param("p", procurement)
        .query(OffsetDateTime.class)
        .optional()
        .map(OffsetDateTime::toInstant)
        .orElse(null);
  }

  /** A saved search with its unsent delivery row, as the matcher leaves them. */
  private UUID matched(UUID subscriber, String name, String frequency, String procurement) {
    var search = namedSearch(jdbc, subscriber, name, frequency);
    delivery(jdbc, search, procurement, Instant.now().minusSeconds(2));
    return search;
  }

  private AlertMatched alert(
      UUID subscriber, UUID search, Frequency frequency, String procurement) {
    return AlertMatched.of(
        new SearchMatch(search, subscriber, frequency),
        Facts.procurement().id(procurement).build());
  }

  private void publish(UUID eventId, AlertMatched alert) {
    template.send(
        TOPIC, alert.subscriberId().toString(), AlertEnvelopes.json(eventId, alert, Instant.now()));
  }

  @Test
  void anInstantAlertArrivesOnceWithTheProcurementLinkAndMarksTheDeliverySent() {
    var subscriber = subscriber(jdbc);
    var search = matched(subscriber, "Notebooks SC", "INSTANT", P1);

    publish(UUID.randomUUID(), alert(subscriber, search, Frequency.INSTANT, P1));

    until(() -> SharedMailpit.messagesTo(addressOf(subscriber)).size() == 1);
    var mail = SharedMailpit.messagesTo(addressOf(subscriber)).getFirst();
    assertThat(mail.subject()).isEqualTo("Nova licitação para a busca \"Notebooks SC\"");
    assertThat(mail.text()).contains(LINK1);
    assertThat(mail.html()).contains(LINK1).contains("Prefeitura de Exemplo");
    until(() -> sentAt(search, P1) != null);
  }

  @Test
  void theSameEventDeliveredAgainStillProducesOneMail() {
    var subscriber = subscriber(jdbc);
    var search = matched(subscriber, "Notebooks SC", "INSTANT", P1);
    var other = matched(subscriber, "Cadeiras", "INSTANT", P2);
    var event = UUID.randomUUID();
    var first = alert(subscriber, search, Frequency.INSTANT, P1);

    publish(event, first);
    until(() -> sentAt(search, P1) != null);
    publish(event, first);
    publish(UUID.randomUUID(), first);
    // same key, same partition: once the marker is out the redeliveries before it were handled
    publish(UUID.randomUUID(), alert(subscriber, other, Frequency.INSTANT, P2));

    until(() -> SharedMailpit.messagesTo(addressOf(subscriber)).size() == 2);
    assertThat(SharedMailpit.messagesTo(addressOf(subscriber)))
        .extracting(SharedMailpit.Mail::subject)
        .containsExactlyInAnyOrder(
            "Nova licitação para a busca \"Notebooks SC\"",
            "Nova licitação para a busca \"Cadeiras\"");
  }

  @Test
  void dailyAlertsWaitForTheDigestThatSendsOneMailPerSubscriber() {
    var ana = subscriber(jdbc);
    var bruno = subscriber(jdbc);
    var notebooks = namedSearch(jdbc, ana, "Notebooks", "DAILY");
    var chairs = namedSearch(jdbc, bruno, "Cadeiras", "DAILY");
    for (var id : List.of(P1, P2, P3)) {
      delivery(jdbc, notebooks, id, Instant.now().minusSeconds(5));
      publish(UUID.randomUUID(), alert(ana, notebooks, Frequency.DAILY, id));
    }
    delivery(jdbc, chairs, P1, Instant.now().minusSeconds(5));
    publish(UUID.randomUUID(), alert(bruno, chairs, Frequency.DAILY, P1));
    until(
        () ->
            jdbc.sql("SELECT count(*) FROM alerts.alert_delivery WHERE detail_path IS NOT NULL")
                    .query(Long.class)
                    .single()
                == 4);
    assertThat(SharedMailpit.messagesTo(addressOf(ana))).isEmpty();

    var result = digest.run();

    assertThat(result).isEqualTo(new SendDigestUseCase.Result(2, 4, 0));
    var anaMails = SharedMailpit.messagesTo(addressOf(ana));
    assertThat(anaMails).hasSize(1);
    assertThat(anaMails.getFirst().subject()).isEqualTo("Resumo diário: 3 novas licitações");
    assertThat(anaMails.getFirst().text())
        .contains("licitacoes/83021808000182-1-000001-2026")
        .contains("licitacoes/83021808000182-1-000002-2026")
        .contains("licitacoes/83021808000182-1-000003-2026");
    assertThat(SharedMailpit.messagesTo(addressOf(bruno))).hasSize(1);
    assertThat(sentAt(notebooks, P1)).isNotNull();
    assertThat(sentAt(chairs, P1)).isNotNull();

    assertThat(digest.run()).isEqualTo(new SendDigestUseCase.Result(0, 0, 0));
    assertThat(SharedMailpit.messagesTo(addressOf(ana))).hasSize(1);
  }

  @Test
  void anUnreachableMailServerIsRetriedThreeTimesThenTheEventGoesToTheDeadLetterTopic()
      throws Exception {
    var subscriber = subscriber(jdbc);
    var search = matched(subscriber, "Notebooks SC", "INSTANT", P1);
    var event = UUID.randomUUID();
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var failuresBefore = failures("instant");
    var workingPort = sender.getPort();
    sender.setPort(closedPort);
    try {
      publish(event, alert(subscriber, search, Frequency.INSTANT, P1));

      var dead =
          TopicReader.readUntil(
              TOPIC + ".dlq",
              all -> all.stream().anyMatch(r -> r.key().equals(subscriber.toString())),
              Duration.ofSeconds(60));
      var record = dead.stream().filter(r -> r.key().equals(subscriber.toString())).findFirst();
      assertThat(record).isPresent();
      assertThat(record.get().value()).contains(search.toString());
      assertThat(
              new String(
                  record.get().headers().lastHeader("x-error-class").value(),
                  StandardCharsets.UTF_8))
          .contains("MailDeliveryException");
      assertThat(record.get().headers().lastHeader("x-original-topic")).isNotNull();
    } finally {
      sender.setPort(workingPort);
    }

    var attempts =
        mockingDetails(instant).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("send"))
            .filter(call -> call.getArgument(0).equals(event))
            .count();
    assertThat(attempts).as("the first try and three retries").isEqualTo(4);
    assertThat(failures("instant") - failuresBefore).as("one per refused try").isEqualTo(4);
    assertThat(sentAt(search, P1)).isNull();
    assertThat(SharedMailpit.messagesTo(addressOf(subscriber))).isEmpty();
  }

  private double failures(String kind) {
    return registry.get("alert_mail_failures_total").tag("kind", kind).counter().count();
  }

  @Test
  void anAlertOlderThanTheMaximumAgeIsIgnoredAndCounted() {
    var subscriber = subscriber(jdbc);
    var stale = matched(subscriber, "Antiga", "INSTANT", P1);
    var fresh = matched(subscriber, "Recente", "INSTANT", P2);
    var staleBefore = registry.get("alert_stale_events_total").counter().count();

    template.send(
        TOPIC,
        subscriber.toString(),
        AlertEnvelopes.json(
            UUID.randomUUID(),
            alert(subscriber, stale, Frequency.INSTANT, P1),
            Instant.now().minus(Duration.ofDays(2))));
    publish(UUID.randomUUID(), alert(subscriber, fresh, Frequency.INSTANT, P2));

    // same key, same partition: once the fresh one is out the stale one was already looked at
    until(() -> SharedMailpit.messagesTo(addressOf(subscriber)).size() == 1);
    assertThat(SharedMailpit.messagesTo(addressOf(subscriber)).getFirst().subject())
        .contains("Recente");
    assertThat(registry.get("alert_stale_events_total").counter().count() - staleBefore)
        .isEqualTo(1);
    assertThat(sentAt(stale, P1)).isNull();
  }
}
