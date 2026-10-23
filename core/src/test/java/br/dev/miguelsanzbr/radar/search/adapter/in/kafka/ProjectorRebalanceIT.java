package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.T0;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.ProcurementProjector;
import br.dev.miguelsanzbr.radar.search.application.ProjectionOutcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rebalance of the projector group on a real broker and database. 10 000 events are on a 12
 * partition topic while one consumer projects them; mid-stream a second instance of the projector
 * (a second container of the same group, running the same listener and projector) joins and, later,
 * leaves without committing its last records, as a crashed instance would. Nothing may be lost, and
 * the redelivery (at-least-once) must be absorbed by the inbox: the projector applies each event
 * once.
 *
 * <p>Run with {@code make messaging-test}; it is tagged {@code messaging}. Its own topic and group
 * keep it apart from the other test classes.
 */
@Tag("messaging")
@KafkaSpringBootTest
@TestPropertySource(
    properties = {
      "radar.search.projector.enabled=true",
      "radar.search.projector.group=search-projector-rebalance-it",
      "radar.outbox.topics.Procurement=radar.procurement.rebalance-it.v1",
      // why: a fresh 12 partition topic can take a while to settle on a loaded broker
      "spring.kafka.producer.properties.delivery.timeout.ms=120000",
      "spring.kafka.producer.properties.request.timeout.ms=30000"
    })
class ProjectorRebalanceIT {

  private static final String TOPIC = "radar.procurement.rebalance-it.v1";
  private static final String GROUP = "search-projector-rebalance-it";
  private static final int AGGREGATES = 2_500;
  private static final int EVENTS = AGGREGATES * 4;
  private static final int PARTITIONS = 12;

  /** Records the second consumer projects and acknowledges before it stops acknowledging. */
  private static final int UNACKNOWLEDGED_AFTER = 1_500;

  @Autowired KafkaTemplate<String, String> template;
  @Autowired JdbcTemplate jdbc;
  @Autowired ProcurementEventsListener listener;

  @Autowired
  @Qualifier("searchKafkaListenerContainerFactory")
  ConcurrentKafkaListenerContainerFactory<String, String> factory;

  @MockitoSpyBean ProcurementProjector projector;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  /** Every partition of the fresh topic must accept writes before the test relies on them. */
  @BeforeEach
  void topicIsReady() {
    var probe =
        envelope(
            "Probe", closed("probe", id(0), 1), JsonMapper.builder().build().createObjectNode());
    await()
        .atMost(Duration.ofSeconds(150))
        .ignoreExceptions()
        .until(
            () -> {
              for (var partition = 0; partition < PARTITIONS; partition++) {
                template.send(TOPIC, partition, "probe", probe).get(10, TimeUnit.SECONDS);
              }
              return true;
            });
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM search." + table, Integer.class);
  }

  @Test
  void aConsumerJoiningAndLeavingMidStreamLosesNothingAndRedeliveriesAreAbsorbed()
      throws Exception {
    var calls = new AtomicInteger();
    var applied = new AtomicInteger();
    var duplicates = new AtomicInteger();
    var deferred = new AtomicInteger();
    var lastCall = new AtomicLong(System.nanoTime());
    var longestGap = new AtomicLong();
    doAnswer(
            invocation -> {
              var now = System.nanoTime();
              longestGap.accumulateAndGet(now - lastCall.getAndSet(now), Math::max);
              calls.incrementAndGet();
              var outcome = (ProjectionOutcome) invocation.callRealMethod();
              switch (outcome) {
                case APPLIED -> applied.incrementAndGet();
                case DUPLICATE -> duplicates.incrementAndGet();
                case DEFERRED -> deferred.incrementAndGet();
              }
              return outcome;
            })
        .when(projector)
        .project(any(), anyLong());

    var started = System.nanoTime();
    publishEverything();
    var published = Duration.ofNanos(System.nanoTime() - started);

    await().atMost(Duration.ofMinutes(3)).until(() -> count("processed_event") >= EVENTS / 5);
    var beforeJoin = count("processed_event");

    var joined = new AtomicInteger();
    var second = factory.createContainer(TOPIC);
    second.getContainerProperties().setGroupId(GROUP);
    second.getContainerProperties().setClientId("search-projector-second");
    // the same setting the @KafkaListener of the projector carries
    second
        .getContainerProperties()
        .getKafkaConsumerProperties()
        .setProperty("auto.offset.reset", "earliest");
    second
        .getContainerProperties()
        .setMessageListener(
            (AcknowledgingMessageListener<String, String>)
                (record, ack) -> {
                  // a consumer that dies before it commits: past this point it projects but never
                  // acknowledges, so its last records come back to the group
                  listener.onMessage(
                      record, joined.incrementAndGet() > UNACKNOWLEDGED_AFTER ? () -> {} : ack);
                });
    var joinedAt = System.nanoTime();
    second.start();
    await().atMost(Duration.ofMinutes(2)).until(() -> joined.get() > 0);
    var joinTook = Duration.ofNanos(System.nanoTime() - joinedAt);
    await().atMost(Duration.ofMinutes(2)).until(() -> joined.get() >= EVENTS / 5);
    var beforeLeave = count("processed_event");
    assertThat(beforeLeave).as("the second consumer left mid-stream").isLessThan(EVENTS);
    second.stop();
    var secondProjected = joined.get();

    await().atMost(Duration.ofMinutes(5)).until(() -> count("processed_event") == EVENTS);
    var drained = Duration.ofNanos(System.nanoTime() - started);
    var lag = awaitGroupLag();

    assertThat(count("procurement_search_view")).as("view rows").isEqualTo(AGGREGATES);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT numero_controle_pncp) FROM search.procurement_search_view",
                Integer.class))
        .as("distinct ids in the view")
        .isEqualTo(AGGREGATES);
    assertThat(count("processed_event")).as("inbox rows").isEqualTo(EVENTS);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT event_id) FROM search.processed_event", Integer.class))
        .as("distinct events in the inbox")
        .isEqualTo(EVENTS);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM search.procurement_detail_view WHERE"
                    + " detail->'closedAt' IS NOT NULL AND jsonb_array_length(detail->'history') = 2",
                Integer.class))
        .as("details with the whole history")
        .isEqualTo(AGGREGATES);
    assertThat(applied.get()).as("events applied by the projector").isEqualTo(EVENTS);
    assertThat(deferred.get()).as("events deferred by disorder").isZero();
    assertThat(duplicates.get())
        .as("records redelivered after the second consumer left without committing")
        .isPositive();
    assertThat(calls.get())
        .as("sanity: every call ended as an application or a duplicate")
        .isEqualTo(applied.get() + duplicates.get());
    assertThat(lag).as("lag of the projector group").isZero();

    var report =
        ("events %d on %d partitions, published in %.1f s; second consumer joined after %d events"
                + " projected and got its first record %.1f s later; it projected %d records, left"
                + " after %d events projected; all %d events projected %.1f s after the start;"
                + " calls to the projector %d, applied %d, redelivered and absorbed by the inbox %d,"
                + " deferred %d; longest pause between two projections %.1f s; view rows %d,"
                + " inbox rows %d")
            .formatted(
                EVENTS,
                PARTITIONS,
                published.toMillis() / 1000.0,
                beforeJoin,
                joinTook.toMillis() / 1000.0,
                secondProjected,
                beforeLeave,
                EVENTS,
                drained.toMillis() / 1000.0,
                calls.get(),
                applied.get(),
                duplicates.get(),
                deferred.get(),
                longestGap.get() / 1e9,
                count("procurement_search_view"),
                count("processed_event"));
    System.out.println("REBALANCE " + report);
    Files.writeString(
        Path.of("target", "rebalance-report.txt"), report + "\n", StandardCharsets.UTF_8);
  }

  /** Discovered, Updated, SituationChanged and Closed for each aggregate. */
  private void publishEverything() throws Exception {
    var sends = new ArrayList<CompletableFuture<?>>(EVENTS);
    for (var i = 0; i < AGGREGATES; i++) {
      var id = id(i);
      var to = i % 2 == 0 ? 4 : 3;
      var first = view(id, "Contratação de serviços " + i, "1000.00", 1);
      var second = view(id, "Aquisição de material " + i, "1000.00", to, 2, T0.plusSeconds(60));
      for (var event :
          List.of(
              discovered("rd" + i, first, 1),
              updated("ru" + i, second, 2),
              situationChanged("rs" + i, id, 2, 1, to),
              closed("rc" + i, id, 3))) {
        sends.add(template.send(TOPIC, id, envelope(event)));
      }
      if (sends.size() >= 2_000) {
        drain(sends);
      }
    }
    drain(sends);
  }

  private static void drain(List<CompletableFuture<?>> sends) throws Exception {
    for (var send : sends) {
      send.get(120, TimeUnit.SECONDS);
    }
    sends.clear();
  }

  /**
   * The records of the topic the group has not committed yet; the last commits may be on the way.
   */
  private long awaitGroupLag() throws Exception {
    var bootstrap = SharedKafka.INSTANCE.getBootstrapServers();
    try (var admin =
            Admin.create(
                Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        var reader =
            new KafkaConsumer<String, String>(
                Map.<String, Object>of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    bootstrap,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class))) {
      var partitions =
          reader.partitionsFor(TOPIC).stream()
              .map(p -> new TopicPartition(TOPIC, p.partition()))
              .toList();
      var end = reader.endOffsets(partitions).values().stream().mapToLong(o -> o).sum();
      var lag = new AtomicLong(end);
      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(300))
          .until(
              () -> {
                var committed =
                    admin
                        .listConsumerGroupOffsets(GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get()
                        .values()
                        .stream()
                        .mapToLong(OffsetAndMetadata::offset)
                        .sum();
                lag.set(end - committed);
                return lag.get() == 0;
              });
      return lag.get();
    }
  }
}
