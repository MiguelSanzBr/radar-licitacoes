package br.dev.miguelsanzbr.radar.outbox.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.dev.miguelsanzbr.radar.outbox.OutboxRelay;
import br.dev.miguelsanzbr.radar.outbox.OutboxWriter;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.TopicReader;
import br.dev.miguelsanzbr.radar.outbox.cdc.RecordAssertions.Expected;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same events go out through the polling relay and through Debezium, and both records go
 * through the same assertions (ADR-0018). The relay is never called while the connector runs. Heavy
 * (three containers and a 2.4 GB image), so it is out of verify: make cdc-test.
 */
@Tag("cdc")
@CdcSpringBootTest
class CdcEquivalenceIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");

  record Sample(String text, String missing, List<String> changed, Map<String, Object> nested) {}

  /** One logical event of the run; the aggregate id gets the run's suffix. */
  record Spec(String type, String id, long version, String eventType, Sample payload) {}

  private static final List<Spec> SPECS =
      List.of(
          new Spec(
              "Procurement",
              "PNCP-IT-A",
              1,
              "ProcurementDiscovered",
              new Sample(
                  "a \"quoted\" é ç", null, List.of(), Map.of("n", 1, "list", List.of(1, 2)))),
          new Spec(
              "Procurement",
              "PNCP-IT-A",
              2,
              "ProcurementUpdated",
              new Sample("second", null, List.of("estimatedValue"), Map.of())),
          new Spec(
              "Alert",
              "sub-IT-1",
              0,
              "AlertMatched",
              new Sample("alert", null, List.of(), Map.of())));

  @Autowired OutboxWriter writer;
  @Autowired OutboxRelay relay;
  @Autowired TransactionTemplate transaction;
  @Autowired Tracer tracer;
  @Autowired JdbcTemplate jdbc;

  private void append(String run) {
    var span = tracer.nextSpan().name("it " + run).start();
    try (var ignored = tracer.withSpan(span)) {
      transaction.executeWithoutResult(
          status ->
              SPECS.forEach(
                  s ->
                      writer.write(
                          s.type(),
                          s.id() + "-" + run,
                          s.version(),
                          s.eventType(),
                          AT,
                          s.payload())));
    } finally {
      span.end();
    }
  }

  private static String topic(Spec spec) {
    return spec.type().equals("Alert") ? "radar.alert.v1" : "radar.procurement.v1";
  }

  /** The records of one run, in the order of {@link #SPECS} (an aggregate stays in order). */
  private static List<ConsumerRecord<String, String>> read(String run) {
    var all = new ArrayList<ConsumerRecord<String, String>>();
    for (var topic : List.of("radar.procurement.v1", "radar.alert.v1")) {
      all.addAll(
          TopicReader.readUntil(
              CdcInfra.KAFKA.getBootstrapServers(),
              topic,
              read -> read.stream().filter(r -> r.key().endsWith(run)).count() >= count(topic),
              Duration.ofSeconds(60)));
    }
    return SPECS.stream()
        .map(
            spec ->
                all.stream()
                    .filter(
                        r -> r.key().equals(spec.id() + "-" + run) && r.topic().equals(topic(spec)))
                    .filter(r -> RecordAssertions.header(r, "eventType").equals(spec.eventType()))
                    .findFirst()
                    .orElseThrow())
        .toList();
  }

  private static long count(String topic) {
    return SPECS.stream().filter(s -> topic(s).equals(topic)).count();
  }

  private String storedTraceparent(ConsumerRecord<String, String> record) {
    return jdbc.queryForObject(
        "SELECT headers ->> 'traceparent' FROM outbox.outbox_event WHERE event_id = ?::uuid",
        String.class,
        RecordAssertions.header(record, "eventId"));
  }

  private void assertRun(List<ConsumerRecord<String, String>> records, String run) {
    for (var i = 0; i < SPECS.size(); i++) {
      var spec = SPECS.get(i);
      var record = records.get(i);
      JsonNode payload = JSON.valueToTree(spec.payload());
      RecordAssertions.assertContract(
          record,
          new Expected(
              topic(spec),
              spec.id() + "-" + run,
              spec.eventType(),
              spec.type(),
              spec.version(),
              AT.toString(),
              payload),
          storedTraceparent(record));
      assertThat(storedTraceparent(record)).isNotNull();
    }
  }

  @Test
  void pollingAndDebeziumPublishTheSameRecords() throws Exception {
    append("polling");
    assertThat(relay.relayOnce()).isEqualTo(SPECS.size());
    var polling = read("polling");

    CdcScripts.enable(jdbc);
    CdcInfra.startConnector();
    append("cdc");
    var cdc = read("cdc");

    assertRun(polling, "polling");
    assertRun(cdc, "cdc");
    for (var i = 0; i < SPECS.size(); i++) {
      RecordAssertions.assertEquivalent(polling.get(i), cdc.get(i));
    }
    // Debezium never sets published_at: the relay stayed out of the cdc rows
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox.outbox_event WHERE aggregate_id LIKE '%-cdc' AND published_at IS NOT NULL",
                Integer.class))
        .isZero();
  }

  /**
   * With the outbox idle and another table taking writes, the slot must still advance: the
   * heartbeat writes a published table, and only that makes the connector confirm the position.
   */
  @Test
  void theHeartbeatMovesTheSlotWhileTheOutboxIsIdle() {
    CdcScripts.enable(jdbc);
    CdcInfra.startConnector();
    jdbc.execute("CREATE TABLE IF NOT EXISTS public.cdc_side (pad text)");
    try {
      // ~5 MB of WAL on a table that is not published
      jdbc.update(
          "INSERT INTO public.cdc_side SELECT repeat('x', 1000) FROM generate_series(1, 5000)");

      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(slotLagBytes()).isLessThan(1_000_000));
    } finally {
      jdbc.execute("DROP TABLE public.cdc_side");
    }
  }

  private long slotLagBytes() {
    return jdbc.queryForObject(
        """
        SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)::bigint
        FROM pg_replication_slots WHERE slot_name = 'radar_outbox'
        """,
        Long.class);
  }
}
