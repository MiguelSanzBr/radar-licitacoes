package br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSituation;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementClosed;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementDiscovered;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementSituationChanged;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementUpdated;
import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@OutboxSpringBootTest
class JdbcOutboxAppenderIT {

  private static final int SEQUENCE = 900_001;

  @Autowired OutboxAppender appender;
  @Autowired ProcurementRepository repository;
  @Autowired TransactionTemplate transaction;
  @Autowired JdbcTemplate jdbc;

  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM outbox.outbox_event");
    jdbc.update(
        "DELETE FROM ingest.procurement_situation_history WHERE numero_controle_pncp = ?",
        id(SEQUENCE).value());
    jdbc.update(
        "DELETE FROM ingest.procurement WHERE numero_controle_pncp = ?", id(SEQUENCE).value());
  }

  private int procurementRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ingest.procurement WHERE numero_controle_pncp = ?",
        Integer.class,
        id(SEQUENCE).value());
  }

  private int outboxRows() {
    return jdbc.queryForObject("SELECT count(*) FROM outbox.outbox_event", Integer.class);
  }

  private void saveAndAppend() {
    var discovery = Procurement.discover(snapshot(id(SEQUENCE)), clockAt(T0));
    repository.save(discovery.procurement());
    appender.append("Procurement", discovery.procurement().revision(), discovery.events());
  }

  @Test
  void exceptionAfterSaveAndAppendLeavesNothingInEitherTable() {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      saveAndAppend();
                      throw new IllegalStateException("boom before commit");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(procurementRows()).isZero();
    assertThat(outboxRows()).isZero();
  }

  @Test
  void commitKeepsOneRowInEachTable() {
    transaction.executeWithoutResult(status -> saveAndAppend());

    assertThat(procurementRows()).isEqualTo(1);
    assertThat(outboxRows()).isEqualTo(1);
  }

  @Test
  void appendOutsideATransactionIsRejected() {
    var discovery = Procurement.discover(snapshot(id(SEQUENCE)), clockAt(T0));

    assertThatThrownBy(() -> appender.append("Procurement", 1, discovery.events()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(outboxRows()).isZero();
  }

  @Test
  void discoveredPayloadAndEnvelopeColumnsHaveTheContractShape() throws Exception {
    var snapshot = snapshot(id(SEQUENCE));
    var discovery = Procurement.discover(snapshot, clockAt(T0));
    transaction.executeWithoutResult(
        status -> appender.append("Procurement", 7, discovery.events()));

    var row = jdbc.queryForMap("SELECT * FROM outbox.outbox_event");
    assertThat(row)
        .containsEntry("aggregate_type", "Procurement")
        .containsEntry("aggregate_id", id(SEQUENCE).value())
        .containsEntry("event_type", "ProcurementDiscovered");
    assertThat(((Number) row.get("attempts")).intValue()).isZero();
    assertThat(((Number) row.get("schema_version")).intValue()).isEqualTo(1);
    assertThat(row.get("published_at")).isNull();
    var eventId = (UUID) row.get("event_id");
    assertThat(eventId.version()).isEqualTo(7);

    JsonNode payload = json.readTree(row.get("payload").toString());
    assertThat(payload.get("aggregateId").asString()).isEqualTo(id(SEQUENCE).value());
    assertThat(payload.get("occurredAt").asString()).isEqualTo("2026-10-22T12:00:00Z");
    var view = payload.get("snapshot");
    assertThat(view.get("id").asString()).isEqualTo(id(SEQUENCE).value());
    assertThat(view.get("estimatedValue").asString()).isEqualTo("1500.50");
    assertThat(view.get("awardedValue").asString()).isEqualTo("1400.00");
    assertThat(view.get("publishedAt").asString()).isEqualTo("2026-10-21T12:00:00Z");
    assertThat(view.get("situation").get("id").asInt()).isEqualTo(1);
    assertThat(view.get("proposalWindow").get("openingAt").asString())
        .isEqualTo("2026-10-23T12:00:00Z");
    assertThat(view.get("objectDescription").asString())
        .isEqualTo("Aquisicao de material de escritorio");
    assertThat(view.get("srp").asBoolean()).isTrue();

    JsonNode headers = json.readTree(row.get("headers").toString());
    assertThat(headers.propertyNames())
        .containsExactlyInAnyOrder("eventType", "schemaVersion", "aggregateVersion", "occurredAt");
    assertThat(headers.get("eventType").asString()).isEqualTo("ProcurementDiscovered");
    assertThat(headers.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(headers.get("aggregateVersion").asLong()).isEqualTo(7);
    assertThat(headers.get("occurredAt").asString()).isEqualTo("2026-10-22T12:00:00Z");
  }

  @Test
  void everyEventOfTheFamilySerializesAndKeepsItsOrder() throws Exception {
    var discovery = Procurement.discover(snapshot(id(SEQUENCE)), clockAt(T0));
    var view = ((ProcurementDiscovered) discovery.events().getFirst()).snapshot();
    var events =
        List.of(
            new ProcurementUpdated(id(SEQUENCE).value(), T0, view, List.of("objectDescription")),
            new ProcurementSituationChanged(
                id(SEQUENCE).value(),
                T0,
                new PncpSituation(1, "a"),
                new PncpSituation(2, "b"),
                T0,
                true),
            new ProcurementClosed(id(SEQUENCE).value(), T0, Instant.parse("2026-10-27T12:00:00Z")));

    transaction.executeWithoutResult(status -> appender.append("Procurement", 3, events));

    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM outbox.outbox_event ORDER BY id", String.class))
        .containsExactly("ProcurementUpdated", "ProcurementSituationChanged", "ProcurementClosed");
    var changed =
        json.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox.outbox_event WHERE event_type = 'ProcurementSituationChanged'",
                String.class));
    assertThat(changed.get("from").get("name").asString()).isEqualTo("a");
    assertThat(changed.get("unexpected").asBoolean()).isTrue();
  }

  @Test
  void appendingNothingWritesNothing() {
    transaction.executeWithoutResult(status -> appender.append("Procurement", 1, List.of()));

    assertThat(outboxRows()).isZero();
  }
}
