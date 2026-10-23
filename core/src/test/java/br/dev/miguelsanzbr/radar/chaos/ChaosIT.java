package br.dev.miguelsanzbr.radar.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Delivery guarantees under faults. A backfill feeds 10 000 notices through the real pipeline
 * (PNCP, ingest, outbox, relay, Kafka, search projector) while Toxiproxy and container restarts
 * break, one after the other, the links the pipeline depends on. When everything has converged the
 * test compares what each stage holds: nothing lost, nothing applied twice.
 *
 * <p>Run alone with {@code make chaos}; it is tagged {@code chaos} and out of the default verify.
 */
@Tag("chaos")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
@SpringBootTest(
    properties = {
      "radar.outbox.relay-enabled=true",
      // why: the daily cleanup is the one cron of the relay, and it must not run during the test
      "radar.outbox.cleanup-cron=-",
      "radar.search.projector.enabled=true",
      // why: a dead-lettered event is a failure of the test, so the recorder is on to count them
      "radar.ops.dlq-recorder.enabled=true",
      "radar.pncp.requests-per-second=1000",
      "radar.ingest.fetch-ahead=1",
      "management.tracing.export.otlp.enabled=false"
    })
@Import(ChaosTestConfig.class)
class ChaosIT {

  private static final String TOPIC = "radar.procurement.v1";
  private static final String GROUP = "search-projector";
  private static final int MODALITY = 6;

  /** A backlog this small is the pipeline keeping up with the feed (two PNCP pages). */
  private static final long DRAINED = 100;

  // the key OutboxRelay takes with pg_try_advisory_xact_lock, split by Postgres in classid and
  // objid
  private static final String RELAY_LOCK_HELD =
      """
      SELECT count(*) FROM pg_locks
      WHERE locktype = 'advisory' AND granted
        AND ((classid::bigint << 32) | objid::bigint) = hashtextextended('radar.outbox.relay', 0)
      """;

  private static final Duration CONVERGENCE = Duration.ofMinutes(5);

  @DynamicPropertySource
  static void pncp(DynamicPropertyRegistry registry) {
    PncpFixture.stubAll();
    registry.add("radar.pncp.base-url", ChaosEnvironment::pncpBaseUrl);
  }

  @Autowired BackfillUseCase backfill;
  @Autowired MeterRegistry meters;

  private ChaosProbe probe;

  /** One fault: injects it, holds it, heals it, and returns once the link works again. */
  private interface Fault {
    void run() throws Exception;
  }

  /**
   * {@code cutsKafkaLinks}: the fault must make the application open new Kafka connections. {@code
   * resendsBatch}: the relay must publish rows again after the fault.
   */
  private record Scenario(
      String name, int atIngested, boolean cutsKafkaLinks, boolean resendsBatch, Fault fault) {}

  private record Result(
      Scenario scenario,
      long startedMillis,
      long healedMillis,
      long recoveredMillis,
      long ingestedAtStart,
      long ingestedDuring,
      long projectedDuring,
      long kafkaConnections,
      ChaosProbe.Sample peak,
      long peakUnprojected,
      long rowsWithErrorDelta,
      int feederRerunsDelta) {}

  private final AtomicInteger feederReruns = new AtomicInteger();
  private volatile boolean feederDone;
  private volatile long firstFaultAt;

  /** When each record that repeats an earlier one of the topic was written (epoch millis). */
  private List<Long> redeliveries = List.of();

  @Test
  void tenThousandEventsConvergeWithNoLossAndNoDuplicateEffectAfterEveryFault() throws Exception {
    try (var probe = new ChaosProbe()) {
      this.probe = probe;
      probe.startSampling();
      var feeder = Thread.ofPlatform().name("chaos-feeder").start(this::feed);

      var results = new ArrayList<Result>();
      for (var scenario : scenarios()) {
        results.add(run(scenario));
      }

      await()
          .atMost(CONVERGENCE)
          .pollInterval(Duration.ofSeconds(1))
          .until(
              () -> {
                var s = probe.latest();
                return feederDone
                    && s != null
                    && s.projected() == PncpFixture.EVENTS
                    && s.pending() == 0;
              });
      feeder.join();

      var finalState = finalState();
      var report = report(results, finalState);
      System.out.println(report);
      Files.writeString(Path.of("target", "chaos-report.txt"), report, StandardCharsets.UTF_8);

      // each fault must have bitten, or the run proves nothing about it
      for (var i = 0; i < results.size(); i++) {
        var r = results.get(i);
        if (r.scenario().cutsKafkaLinks()) {
          assertThat(r.kafkaConnections())
              .as("Kafka connections opened during %s", r.scenario().name())
              .isPositive();
        }
        if (r.scenario().resendsBatch()) {
          assertThat(redelivered(results, i))
              .as("records published again after %s", r.scenario().name())
              .isPositive();
        }
      }

      // every stage holds all 10 000
      assertThat(finalState.ingested()).as("ingest.procurement").isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.outbox()).as("outbox rows").isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.published()).as("published outbox rows").isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.processed()).as("inbox rows").isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.view()).as("search view rows").isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.detail()).as("detail view rows").isEqualTo(PncpFixture.EVENTS);
      // integrity checks: the primary keys and unique indexes already forbid these
      assertThat(finalState.ingestedDistinct()).isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.outboxDistinct()).isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.processedDistinct()).isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.viewDistinct()).isEqualTo(PncpFixture.EVENTS);
      // the proof of no duplicate effect: the projector times an event only when it applies it, and
      // the topic holds more records than that, so the inbox turned the surplus away. The bound is
      // "at most" because an event whose commit was acknowledged to nobody (Postgres stopping
      // inside the commit) is applied once and read again as a duplicate, so it is not timed
      assertThat(finalState.applied())
          .as("events applied by the projector")
          .isLessThanOrEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.lostBeforeProjection()).as("published, not in the inbox").isZero();
      assertThat(finalState.lostFromView()).as("ingested, not in the view").isZero();
      assertThat(finalState.strayInbox()).as("in the inbox, not in the outbox").isZero();
      assertThat(finalState.wrongHistory()).as("detail with a history other than 1").isZero();
      assertThat(finalState.poison()).as("rows that spent every attempt").isZero();
      assertThat(finalState.topicDistinct())
          .as("events on the topic")
          .isEqualTo(PncpFixture.EVENTS);
      assertThat(finalState.groupLag()).as("lag of the projector group").isZero();
      assertThat(finalState.dlqRows()).as("rows of ops.dlq_message").isZero();
      assertThat(finalState.dlqRecords()).as("records on the dead-letter topics").isZero();
    }
  }

  /** Re-runs each day until it is done, as the nightly job would after a failed run. */
  private void feed() {
    for (var day : PncpFixture.days()) {
      var finished = false;
      while (!finished) {
        try {
          finished = backfill.isDone(day, MODALITY);
          if (!finished) {
            backfill.run(day, day, MODALITY);
          }
        } catch (RuntimeException e) {
          if (feederReruns.incrementAndGet() <= 3 || feederReruns.get() % 20 == 0) {
            System.out.println("CHAOS feeder run " + feederReruns.get() + " failed: " + e);
          }
          ChaosEnvironment.pause(Duration.ofSeconds(1));
        }
      }
    }
    feederDone = true;
  }

  private List<Scenario> scenarios() {
    return List.of(
        new Scenario(
            "a. latência de 2 s app-Postgres (20 s)",
            700,
            false,
            false,
            () -> withLatency(ChaosEnvironment.PG, Duration.ofSeconds(20), 2000)),
        new Scenario(
            "b. corte de 10 s app-Kafka",
            2000,
            true,
            false,
            () -> {
              ChaosEnvironment.KAFKA_PROXY.disable();
              try {
                ChaosEnvironment.pause(Duration.ofSeconds(10));
              } finally {
                ChaosEnvironment.KAFKA_PROXY.enable();
              }
            }),
        new Scenario(
            "c. 5% de conexões Kafka reiniciadas (30 s)", 3300, true, false, this::resetKafkaLinks),
        new Scenario(
            "d. broker Kafka reiniciado",
            4600,
            true,
            false,
            () -> {
              ChaosEnvironment.restart(ChaosEnvironment.KAFKA, Duration.ofSeconds(3));
              ChaosEnvironment.awaitKafka(Duration.ofSeconds(90));
            }),
        new Scenario(
            "e. Postgres reiniciado no meio do lote do relay",
            5900,
            false,
            true,
            this::restartPostgresMidBatch),
        new Scenario(
            "f. latência de 2 s app-PNCP (20 s)",
            7200,
            false,
            false,
            () -> withLatency(ChaosEnvironment.PNCP, Duration.ofSeconds(20), 2000)));
  }

  private static void withLatency(Proxy proxy, Duration hold, long millis) throws Exception {
    var latency = proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, millis);
    try {
      ChaosEnvironment.pause(hold);
    } finally {
      latency.remove();
    }
  }

  /**
   * Toxiproxy has no packet loss toxic. This is the closest model that is true to what Toxiproxy
   * does: a {@code reset_peer} toxic at toxicity 0.05, both ways, for 30 s. Toxiproxy rolls the
   * toxicity once per connection, when the toxic meets it, so the toxic is added and removed every
   * 500 ms to roll the live connections again and again. Each roll resets about one connection in
   * twenty (RST at once). That is a link that now and then drops a whole connection, not a link
   * that loses single packets.
   */
  private void resetKafkaLinks() throws Exception {
    try {
      for (var cycle = 0; cycle < 60; cycle++) {
        ToxiproxyHttp.addToxic(
            "kafka", "reset-down", "reset_peer", "downstream", 0.05, "{\"timeout\":0}");
        ToxiproxyHttp.addToxic(
            "kafka", "reset-up", "reset_peer", "upstream", 0.05, "{\"timeout\":0}");
        ChaosEnvironment.pause(Duration.ofMillis(500));
        ToxiproxyHttp.removeToxic("kafka", "reset-down");
        ToxiproxyHttp.removeToxic("kafka", "reset-up");
      }
    } finally {
      ToxiproxyHttp.removeQuietly("kafka", "reset-down");
      ToxiproxyHttp.removeQuietly("kafka", "reset-up");
    }
  }

  /**
   * The relay publishes the whole selected batch inside one transaction, holding the advisory lock
   * of the relay, and marks the rows published with a single update at the end. Slow acks keep it
   * in that state; the restart happens once the relay lock is held and a backlog is waiting, so
   * rows already sent to Kafka are not marked and the whole batch goes out again.
   */
  private void restartPostgresMidBatch() throws Exception {
    var slow =
        ChaosEnvironment.KAFKA_PROXY.toxics().latency("slow-acks", ToxicDirection.DOWNSTREAM, 100);
    try {
      await()
          .atMost(Duration.ofSeconds(60))
          .pollInterval(Duration.ofMillis(100))
          .ignoreExceptions()
          .until(() -> probe.scalar(RELAY_LOCK_HELD) > 0 && probe.latest().pending() >= DRAINED);
      ChaosEnvironment.restart(ChaosEnvironment.POSTGRES, Duration.ofSeconds(5));
      ChaosEnvironment.awaitPostgres(Duration.ofSeconds(90));
    } finally {
      slow.remove();
    }
  }

  private Result run(Scenario scenario) throws Exception {
    await()
        .atMost(Duration.ofMinutes(10))
        .pollInterval(Duration.ofMillis(250))
        .until(
            () -> {
              var s = probe.latest();
              return s != null && s.ingested() >= scenario.atIngested();
            });
    assertThat(feederDone)
        .as("the feed must still be running when %s starts", scenario.name())
        .isFalse();
    var atStart = probe.latest();
    var errorsBefore = rowsWithError();
    var rerunsBefore = feederReruns.get();
    var connectionsBefore = kafkaConnections();
    var started = probe.nowMillis();
    firstFaultAt = firstFaultAt == 0 ? started : firstFaultAt;

    scenario.fault().run();

    var healed = probe.nowMillis();
    // a sample taken after the heal, within the band the steady feed leaves
    await()
        .atMost(CONVERGENCE)
        .pollInterval(Duration.ofMillis(250))
        .until(
            () -> {
              var s = probe.latest();
              return s != null && s.atMillis() > healed && s.unprojected() <= DRAINED;
            });
    var recovered = probe.latest().atMillis();
    var whileBroken = probe.peak(started, healed);
    return new Result(
        scenario,
        started,
        healed,
        recovered,
        atStart.ingested(),
        whileBroken.ingested() - atStart.ingested(),
        whileBroken.projected() - atStart.projected(),
        kafkaConnections() - connectionsBefore,
        probe.peak(started, recovered),
        probe.peakUnprojected(started, recovered),
        rowsWithError() - errorsBefore,
        feederReruns.get() - rerunsBefore);
  }

  /** Connections the application opened to Kafka so far: a fault that cuts links makes it grow. */
  private long kafkaConnections() {
    return Math.round(
        meters.getMeters().stream()
            .filter(
                m ->
                    m.getId().getName().equals("kafka.producer.connection.creation.total")
                        || m.getId().getName().equals("kafka.consumer.connection.creation.total"))
            .flatMap(m -> StreamSupport.stream(m.measure().spliterator(), false))
            .mapToDouble(m -> m.getValue())
            .sum());
  }

  private long rowsWithError() {
    return await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(250))
        .ignoreExceptions()
        .until(
            () ->
                probe.scalar(
                    "SELECT count(*) FROM outbox.outbox_event WHERE last_error IS NOT NULL"),
            n -> true);
  }

  private record FinalState(
      long ingested,
      long ingestedDistinct,
      long outbox,
      long outboxDistinct,
      long published,
      long processed,
      long processedDistinct,
      long view,
      long viewDistinct,
      long detail,
      long lostBeforeProjection,
      long lostFromView,
      long strayInbox,
      long wrongHistory,
      long poison,
      long rowsWithError,
      long topicRecords,
      long topicDistinct,
      long groupLag,
      long dlqRows,
      long dlqRecords,
      long applied) {}

  private FinalState finalState() throws Exception {
    var topic = readTopic();
    return new FinalState(
        probe.scalar("SELECT count(*) FROM ingest.procurement"),
        probe.scalar("SELECT count(DISTINCT numero_controle_pncp) FROM ingest.procurement"),
        probe.scalar("SELECT count(*) FROM outbox.outbox_event"),
        probe.scalar("SELECT count(DISTINCT event_id) FROM outbox.outbox_event"),
        probe.scalar("SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NOT NULL"),
        probe.scalar(
            "SELECT count(*) FROM search.processed_event WHERE consumer = '" + GROUP + "'"),
        probe.scalar(
            "SELECT count(DISTINCT event_id) FROM search.processed_event WHERE consumer = '"
                + GROUP
                + "'"),
        probe.scalar("SELECT count(*) FROM search.procurement_search_view"),
        probe.scalar(
            "SELECT count(DISTINCT numero_controle_pncp) FROM search.procurement_search_view"),
        probe.scalar("SELECT count(*) FROM search.procurement_detail_view"),
        probe.scalar(
            "SELECT count(*) FROM outbox.outbox_event o WHERE o.published_at IS NOT NULL AND NOT"
                + " EXISTS (SELECT 1 FROM search.processed_event p WHERE p.event_id = o.event_id)"),
        probe.scalar(
            "SELECT count(*) FROM ingest.procurement i WHERE NOT EXISTS (SELECT 1 FROM"
                + " search.procurement_search_view v WHERE v.numero_controle_pncp ="
                + " i.numero_controle_pncp)"),
        probe.scalar(
            "SELECT count(*) FROM search.processed_event p WHERE NOT EXISTS (SELECT 1 FROM"
                + " outbox.outbox_event o WHERE o.event_id = p.event_id)"),
        probe.scalar(
            "SELECT count(*) FROM search.procurement_detail_view WHERE"
                + " jsonb_array_length(detail->'history') <> 1"),
        probe.scalar("SELECT count(*) FROM outbox.outbox_event WHERE attempts >= 10"),
        rowsWithError(),
        topic.records(),
        topic.distinct(),
        topic.groupLag(),
        probe.scalar("SELECT count(*) FROM ops.dlq_message"),
        topic.dlqRecords(),
        meters.get("projection_lag_seconds").timer().count());
  }

  private record Topic(long records, long distinct, long groupLag, long dlqRecords) {}

  /** Reads the topic from the start and the committed offsets of the projector group. */
  private Topic readTopic() throws Exception {
    var bootstrap = ChaosEnvironment.kafkaBootstrap();
    try (var consumer =
            new KafkaConsumer<String, String>(
                Map.<String, Object>of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    bootstrap,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                    false));
        var admin =
            Admin.create(
                Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      var partitions =
          consumer.partitionsFor(TOPIC).stream()
              .map(p -> new TopicPartition(TOPIC, p.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      var end = consumer.endOffsets(partitions);
      long records = 0;
      var ids = new HashSet<String>();
      var again = new ArrayList<Long>();
      var deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
      while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))
          && System.nanoTime() < deadline) {
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          records++;
          var id =
              new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
          if (!ids.add(id)) {
            again.add(record.timestamp());
          }
        }
      }
      var endSum = end.values().stream().mapToLong(o -> o).sum();
      // the commit of the last acknowledged records may still be on its way
      var lag = endSum;
      var commitDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (lag != 0 && System.nanoTime() < commitDeadline) {
        var committed =
            admin
                .listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata()
                .get()
                .values()
                .stream()
                .mapToLong(OffsetAndMetadata::offset)
                .sum();
        lag = endSum - committed;
        if (lag != 0) {
          ChaosEnvironment.pause(Duration.ofMillis(500));
        }
      }
      redeliveries = again;
      var dlq =
          Stream.of(TOPIC + ".dlq", "radar.alert.v1.dlq")
              .flatMap(name -> consumer.partitionsFor(name).stream())
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      var dlqRecords = consumer.endOffsets(dlq).values().stream().mapToLong(o -> o).sum();
      return new Topic(records, ids.size(), lag, dlqRecords);
    }
  }

  private String report(List<Result> results, FinalState f) {
    var out = new StringBuilder("CHAOS_REPORT\n");
    line(out, "events fed (fixture): %d", PncpFixture.EVENTS);
    line(out, "ingest.procurement rows: %d (distinct %d)", f.ingested(), f.ingestedDistinct());
    line(out, "outbox rows: %d (distinct event ids %d)", f.outbox(), f.outboxDistinct());
    line(out, "outbox published: %d", f.published());
    line(
        out,
        "inbox rows (search-projector): %d (distinct %d)",
        f.processed(),
        f.processedDistinct());
    line(out, "search view rows: %d (distinct %d)", f.view(), f.viewDistinct());
    line(out, "detail view rows: %d", f.detail());
    line(
        out,
        "lost: published without inbox row %d, ingested without view row %d",
        f.lostBeforeProjection(),
        f.lostFromView());
    line(
        out,
        "duplicate effects: inbox rows without outbox row %d, detail with history != 1: %d",
        f.strayInbox(),
        f.wrongHistory());
    line(
        out,
        "poison rows: %d; outbox rows that recorded an error: %d",
        f.poison(),
        f.rowsWithError());
    line(
        out,
        "topic records: %d (distinct %d, redelivered %d); projector group lag: %d",
        f.topicRecords(),
        f.topicDistinct(),
        f.topicRecords() - f.topicDistinct(),
        f.groupLag());
    line(
        out,
        "events applied by the projector (projection_lag_seconds count): %d; records the inbox turned away: %d",
        f.applied(),
        f.topicRecords() - f.applied());
    line(
        out,
        "dead letters: %d records on the DLQ topics, %d rows in ops.dlq_message",
        f.dlqRecords(),
        f.dlqRows());
    line(out, "feeder re-runs after a failed run: %d", feederReruns.get());
    line(
        out,
        "steady band before the first fault: peak unprojected %d",
        probe.peakUnprojected(0, firstFaultAt));
    line(
        out,
        "scenario | ingested at start | outage ms | recovery ms | ingested during | projected during"
            + " | kafka connections opened | peak pending | peak oldest s | peak unprojected |"
            + " rows with error | feeder re-runs | redelivered records");
    for (var i = 0; i < results.size(); i++) {
      var r = results.get(i);
      line(
          out,
          "%s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d",
          r.scenario().name(),
          r.ingestedAtStart(),
          r.healedMillis() - r.startedMillis(),
          r.recoveredMillis() - r.healedMillis(),
          r.ingestedDuring(),
          r.projectedDuring(),
          r.kafkaConnections(),
          r.peak().pending(),
          r.peak().oldestPendingSeconds(),
          r.peakUnprojected(),
          r.rowsWithErrorDelta(),
          r.feederRerunsDelta(),
          redelivered(results, i));
    }
    var spread = new TreeMap<Long, Long>();
    redeliveries.forEach(at -> spread.merge((at - probe.epochMillis(0)) / 1000, 1L, Long::sum));
    line(out, "redeliveries by second since start: %s", spread);
    var all = probe.peak(0, probe.nowMillis());
    line(
        out,
        "overall peaks: pending %d, oldest pending %d s; test took %d s",
        all.pending(),
        all.oldestPendingSeconds(),
        probe.nowMillis() / 1000);
    return out.toString();
  }

  /**
   * Records of the topic that repeat an earlier one and were written from the start of scenario
   * {@code i} to the start of the next one: a republish can come after the pipeline is already back
   * in its band, so a scenario owns everything up to the next.
   */
  private long redelivered(List<Result> results, int i) {
    var from = probe.epochMillis(results.get(i).startedMillis());
    var to =
        probe.epochMillis(
            i + 1 < results.size() ? results.get(i + 1).startedMillis() : probe.nowMillis());
    return redeliveries.stream().filter(at -> at >= from && at < to).count();
  }

  private static void line(StringBuilder out, String format, Object... values) {
    out.append(format.formatted(values)).append('\n');
  }
}
