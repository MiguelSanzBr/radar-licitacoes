package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData;
import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox.EventSchemas;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementEvent;
import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import com.networknt.schema.InputFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Procurement events travel the real way: ingest appends to the outbox, the relay publishes to
 * Kafka, the alerts listener matches and the relay publishes the resulting AlertMatched.
 */
@KafkaSpringBootTest
@TestPropertySource(
    properties = {
      "radar.alerts.matcher.enabled=true",
      "radar.alerts.matcher.retry-interval=10ms",
      "radar.alerts.matcher.max-retries=2",
      "radar.alerts.matcher.outage-retry-interval=20ms"
    })
class ProcurementEventsForAlertsIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TOPIC = "radar.procurement.v1";
  private static final int SEQUENCE = 910_001;

  // why: the matcher drops events older than its maximum age, so the events must be born now
  private static final Instant NOW = Instant.now();

  @Autowired OutboxAppender appender;
  @Autowired OutboxRelay relay;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> template;
  @Autowired KafkaListenerEndpointRegistry registry;
  @MockitoSpyBean MatchProcurementUseCase useCase;

  private UUID subscriber;
  private UUID savedSearch;

  @BeforeEach
  void aSubscriberWatchingOfficeSupplies() {
    // the group starts at the end of the topic, so it must own its partitions before anything is
    // sent
    var container = registry.getListenerContainer("alerts-matcher");
    until(() -> container.getAssignedPartitions().size() == 12);
    theMatcherHasCaughtUp();
    jdbc.sql(
            "TRUNCATE alerts.alert_delivery, alerts.saved_search, alerts.verification_token,"
                + " alerts.idempotency_key, alerts.subscriber, alerts.processed_event")
        .update();
    jdbc.sql("DELETE FROM outbox.outbox_event").update();
    subscriber = MatcherData.subscriber(jdbc);
    savedSearch = MatcherData.search(jdbc, subscriber, "escritorio", "{\"uf\":\"SC\"}");
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  /**
   * Events still on the topic that the matcher has not committed. A test must leave none behind, or
   * a late event would be matched while the next test runs.
   */
  private static long lag() throws Exception {
    try (var admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedKafka.INSTANCE.getBootstrapServers()))) {
      var committed =
          admin.listConsumerGroupOffsets("alerts-matcher").partitionsToOffsetAndMetadata().get();
      var partitions =
          IntStream.range(0, 12)
              .mapToObj(partition -> new TopicPartition(TOPIC, partition))
              .toList();
      var ends =
          admin
              .listOffsets(
                  partitions.stream()
                      .collect(Collectors.toMap(Function.identity(), tp -> OffsetSpec.latest())))
              .all()
              .get();
      // a partition the group never committed has nothing it was asked to catch up with
      return partitions.stream()
          .filter(committed::containsKey)
          .mapToLong(tp -> ends.get(tp).offset() - committed.get(tp).offset())
          .sum();
    }
  }

  @AfterEach
  void theMatcherHasCaughtUp() {
    until(
        () -> {
          try {
            return lag() == 0;
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
  }

  private long alertRows() {
    return count("outbox.outbox_event WHERE aggregate_type = 'Alert'");
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

  /**
   * Appends the events as the ingest transaction would and sends them to the broker. A publish that
   * times out leaves the row pending, so the relay runs until nothing of ingest is left.
   */
  private void publish(long version, List<ProcurementEvent> events) {
    transaction.executeWithoutResult(status -> appender.append("Procurement", version, events));
    until(
        () -> {
          relay.relayOnce();
          return count(
                  "outbox.outbox_event WHERE aggregate_type = 'Procurement'"
                      + " AND published_at IS NULL")
              == 0;
        });
  }

  private Procurement discover() {
    var discovery = Procurement.discover(snapshot(id(SEQUENCE)), clockAt(NOW));
    publish(1, discovery.events());
    return discovery.procurement();
  }

  @Test
  void aDiscoveredProcurementProducesOneDeliveryAndOneValidAlertOnTheAlertTopic() {
    discover();

    until(() -> alertRows() == 1);
    var delivery = jdbc.sql("SELECT * FROM alerts.alert_delivery").query().singleRow();
    assertThat(delivery)
        .containsEntry("saved_search_id", savedSearch)
        .containsEntry("procurement_id", id(SEQUENCE).value());

    assertThat(relay.relayOnce()).isEqualTo(1);
    var alerts =
        TopicReader.readUntil(
            "radar.alert.v1",
            all -> all.stream().anyMatch(r -> r.key().equals(subscriber.toString())),
            Duration.ofSeconds(30));
    var mine = alerts.stream().filter(r -> r.key().equals(subscriber.toString())).toList();
    assertThat(mine).hasSize(1);
    var envelope = mine.getFirst().value();
    assertThat(EventSchemas.load("envelope").validate(envelope, InputFormat.JSON)).isEmpty();
    assertThat(EventSchemas.load("alert-matched.v1").validate(envelope, InputFormat.JSON))
        .isEmpty();
    var payload = JSON.readTree(envelope).get("payload");
    assertThat(payload.get("savedSearchId").asString()).isEqualTo(savedSearch.toString());
    assertThat(payload.get("procurementId").asString()).isEqualTo(id(SEQUENCE).value());
  }

  @Test
  void redeliveriesAndAnUpdateOfTheSameProcurementLeaveOneDeliveryAndOneAlert() {
    var procurement = discover();
    until(() -> count("alerts.processed_event") == 1);
    var eventId =
        jdbc.sql("SELECT event_id FROM outbox.outbox_event WHERE aggregate_type = 'Procurement'")
            .query(String.class)
            .single();
    var discovered =
        TopicReader.readUntil(
                TOPIC,
                all -> all.stream().anyMatch(r -> r.value().contains(eventId)),
                Duration.ofSeconds(30))
            .stream()
            .filter(r -> r.value().contains(eventId))
            .findFirst()
            .orElseThrow();
    // the headers go along: other tests read this shared topic and expect them on every record
    template.send(
        new ProducerRecord<>(
            TOPIC, null, discovered.key(), discovered.value(), discovered.headers()));
    template.send(
        new ProducerRecord<>(
            TOPIC, null, discovered.key(), discovered.value(), discovered.headers()));

    var updated =
        procurement.applySnapshot(
            snapshot(id(SEQUENCE), 1, "Aquisicao de material de escritorio e papel"),
            clockAt(NOW.plusSeconds(60)));
    publish(2, updated);

    // same key, same partition: once the update is in the inbox the redeliveries before it are done
    until(() -> count("alerts.processed_event") == 2);
    assertThat(count("alerts.alert_delivery")).isEqualTo(1);
    assertThat(alertRows()).isEqualTo(1);
  }

  @Test
  void situationOnlyUpdatesAreConsumedWithoutMatchingAndTheLaterTextUpdateStillIs() {
    var procurement = discover();
    until(() -> count("alerts.processed_event") == 1);

    publish(
        2,
        procurement.applySnapshot(
            snapshot(id(SEQUENCE), 2, "Aquisicao de material de escritorio"),
            clockAt(NOW.plusSeconds(60))));
    publish(
        3,
        procurement.applySnapshot(
            snapshot(id(SEQUENCE), 2, "Aquisicao de material de escritorio e papel"),
            clockAt(NOW.plusSeconds(120))));

    // same key, same partition: once the text update is in the inbox the situation events before
    // it were consumed, and only the discovery and the text update are there
    until(() -> count("alerts.processed_event") == 2);
    assertThat(count("alerts.alert_delivery")).isEqualTo(1);
  }

  @Test
  void aMessageThatCannotBeReadIsSkippedWithoutRetriesAndTheNextOneIsHandled() {
    var poison = new ProducerRecord<>(TOPIC, id(SEQUENCE).value(), "not json");
    poison.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    template.send(poison);

    discover();

    until(() -> count("alerts.alert_delivery") == 1);
  }

  @Test
  void aFailureWhileMatchingIsRetriedUntilTheEventIsMatched() {
    doThrow(new IllegalStateException("database blip"))
        .doCallRealMethod()
        .when(useCase)
        .onDiscovered(any(), any());

    discover();

    until(() -> count("alerts.alert_delivery") == 1);
    var attempts =
        mockingDetails(useCase).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("onDiscovered"))
            .map(call -> call.getArgument(0).toString())
            .toList();
    var eventId =
        jdbc.sql("SELECT event_id FROM outbox.outbox_event WHERE aggregate_type = 'Procurement'")
            .query(String.class)
            .single();
    assertThat(attempts.stream().filter(eventId::equals).count()).isGreaterThanOrEqualTo(2);
    assertThat(count("alerts.processed_event")).isEqualTo(1);
  }

  @Test
  void aDatabaseOutageLongerThanTheBoundedRetriesStillEndsWithTheEventMatched() {
    var outage = new DataAccessResourceFailureException("connection refused");
    doThrow(outage, outage, outage, outage, outage)
        .doCallRealMethod()
        .when(useCase)
        .onDiscovered(any(), any());

    discover();

    until(() -> count("alerts.alert_delivery") == 1);
    var eventId =
        jdbc.sql("SELECT event_id FROM outbox.outbox_event WHERE aggregate_type = 'Procurement'")
            .query(String.class)
            .single();
    var attempts =
        mockingDetails(useCase).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("onDiscovered"))
            .filter(call -> call.getArgument(0).toString().equals(eventId))
            .count();
    assertThat(attempts).isGreaterThan(3);
    assertThat(count("alerts.processed_event")).isEqualTo(1);
  }

  @Test
  void anEventOlderThanTheMaximumAgeIsSkippedWithoutTouchingTheInbox() {
    var stale =
        Procurement.discover(snapshot(id(SEQUENCE)), clockAt(NOW.minus(Duration.ofDays(2))));
    publish(1, stale.events());

    discover();

    until(() -> count("alerts.alert_delivery") == 1);
    assertThat(count("alerts.processed_event")).as("only the fresh event").isEqualTo(1);
  }
}
