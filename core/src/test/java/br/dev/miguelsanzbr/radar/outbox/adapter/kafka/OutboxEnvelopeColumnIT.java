package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.SharedPostgres;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import br.dev.miguelsanzbr.radar.outbox.OutboxRows;
import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.outbox.OutboxWriter;
import br.dev.miguelsanzbr.radar.outbox.cdc.CdcScripts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Debezium ships the {@code envelope} column as the record value, so the column must be, field by
 * field, the envelope the polling relay builds from the other columns.
 */
@OutboxSpringBootTest
class OutboxEnvelopeColumnIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");

  record Sample(String text, String missing, List<String> changed, Map<String, Object> nested) {}

  @Autowired OutboxWriter writer;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void enableCdcAndClean() {
    CdcScripts.enable(jdbc);
    jdbc.update("DELETE FROM outbox.outbox_event");
  }

  @AfterAll
  static void leaveTheSharedDatabaseAsFlywayMadeIt() throws Exception {
    try (var connection = SharedPostgres.INSTANCE.createConnection("")) {
      CdcScripts.disable(connection);
    }
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  /** The value the relay would publish for the stored row, taken from a captured record. */
  private String relayValue(long id) throws Exception {
    var template = mock(KafkaTemplate.class);
    var sent = new AtomicReference<ProducerRecord<String, String>>();
    when(template.send(any(ProducerRecord.class)))
        .thenAnswer(
            call -> {
              sent.set(call.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    var publisher =
        new KafkaEventPublisher(
            template,
            new KafkaPublisherProperties(
                Map.of("Procurement", "radar.procurement.v1", "Alert", "radar.alert.v1"),
                Duration.ofSeconds(1),
                1),
            mock(TraceparentExtractor.class));
    var message =
        jdbc.queryForObject(
            """
            SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                   payload::text, headers::text, created_at
            FROM outbox.outbox_event WHERE id = ?
            """,
            (rs, row) ->
                new OutboxMessage(
                    rs.getLong("id"),
                    rs.getObject("event_id", java.util.UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    rs.getInt("schema_version"),
                    rs.getString("payload"),
                    JSON.readValue(
                        rs.getString("headers"), new TypeReference<Map<String, Object>>() {}),
                    rs.getTimestamp("created_at").toInstant(),
                    0),
            id);
    publisher.publish(message);
    return sent.get().value();
  }

  private String envelopeColumn(long id) {
    return jdbc.queryForObject(
        "SELECT envelope::text FROM outbox.outbox_event WHERE id = ?", String.class, id);
  }

  @Test
  void rowWrittenByTheWriterHasTheEnvelopeTheRelayPublishes() throws Exception {
    transaction.executeWithoutResult(
        status ->
            writer.write(
                "Procurement",
                "PNCP-1",
                4,
                "ProcurementUpdated",
                AT,
                new Sample("a \"quoted\" é", null, List.of(), Map.of("n", 1))));
    var id = jdbc.queryForObject("SELECT max(id) FROM outbox.outbox_event", Long.class);

    var column = JSON.readTree(envelopeColumn(id));

    assertThat(column).isEqualTo(JSON.readTree(relayValue(id)));
    assertThat(column.get("aggregateVersion").asInt()).isEqualTo(4);
    assertThat(column.get("payload").get("missing").isNull()).isTrue();
    assertThat(column.get("payload").get("changed").isArray()).isTrue();
    assertThat(column.get("traceparent").isNull()).isTrue();
  }

  @Test
  void aStoredTraceparentTravelsInTheEnvelopeBody() throws Exception {
    var eventId =
        OutboxRows.insertWithHeaders(
            jdbc,
            "PNCP-2",
            "ProcurementDiscovered",
            "{\"aggregateVersion\":0,\"occurredAt\":\"2026-10-21T08:00:00.123456Z\","
                + "\"traceparent\":\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"}");
    var id =
        jdbc.queryForObject(
            "SELECT id FROM outbox.outbox_event WHERE event_id = ?", Long.class, eventId);

    var column = JSON.readTree(envelopeColumn(id));

    assertThat(column).isEqualTo(JSON.readTree(relayValue(id)));
    assertThat(column.get("traceparent").asString())
        .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    assertThat(column.get("occurredAt").asString()).isEqualTo("2026-10-21T08:00:00.123456Z");
  }

  @Test
  void onlyInsertsOfTheContractColumnsReachTheReplicationSlot() {
    var publication =
        jdbc.queryForMap(
            "SELECT pubinsert, pubupdate, pubdelete, pubtruncate FROM pg_publication WHERE pubname = 'radar_outbox'");
    var columns =
        jdbc.queryForObject(
            "SELECT array_to_string(attnames, ',') FROM pg_publication_tables WHERE pubname = 'radar_outbox' AND tablename = 'outbox_event'",
            String.class);

    assertThat(publication)
        .containsEntry("pubinsert", true)
        .containsEntry("pubupdate", false)
        .containsEntry("pubdelete", false)
        .containsEntry("pubtruncate", false);
    assertThat(
            count(
                "SELECT count(*) FROM pg_publication_tables WHERE pubname = 'radar_outbox' AND tablename = 'cdc_heartbeat'"))
        .isEqualTo(1);
    // the router needs these only: the stored payload and headers are not decoded or sent
    assertThat(columns.split(","))
        .containsExactlyInAnyOrder(
            "id",
            "event_id",
            "aggregate_type",
            "aggregate_id",
            "event_type",
            "schema_version",
            "envelope");
  }

  @Test
  void enablingTwiceChangesNothing() {
    CdcScripts.enable(jdbc);
    CdcScripts.enable(jdbc);

    assertThat(count("SELECT count(*) FROM pg_publication WHERE pubname = 'radar_outbox'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'outbox' AND column_name = 'envelope'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM pg_tables WHERE tablename = 'cdc_heartbeat'"))
        .isEqualTo(1);
  }

  @Test
  void disablingRemovesEverythingAndPollingPaysNothing() {
    CdcScripts.disable(jdbc);

    assertThat(count("SELECT count(*) FROM pg_publication WHERE pubname = 'radar_outbox'"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'outbox' AND column_name = 'envelope'"))
        .isZero();
    assertThat(count("SELECT count(*) FROM pg_proc WHERE proname = 'build_envelope'")).isZero();
    assertThat(count("SELECT count(*) FROM pg_tables WHERE tablename = 'cdc_heartbeat'")).isZero();
    // the table still takes the relay's rows
    assertThat(OutboxRows.insert(jdbc, "A", "ProcurementUpdated")).isPositive();

    CdcScripts.disable(jdbc);
    CdcScripts.enable(jdbc);
  }

  @Test
  void disablingDropsTheInactiveSlotSoItStopsRetainingWal() {
    jdbc.queryForList("SELECT pg_create_logical_replication_slot('radar_outbox', 'pgoutput')");

    CdcScripts.disable(jdbc);

    assertThat(count("SELECT count(*) FROM pg_replication_slots WHERE slot_name = 'radar_outbox'"))
        .isZero();
    CdcScripts.enable(jdbc);
  }
}
