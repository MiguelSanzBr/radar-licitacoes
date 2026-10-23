package br.dev.miguelsanzbr.radar.alerts.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox.EventSchemas;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import com.networknt.schema.InputFormat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ProcurementEventsForAlertsListenerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private final MatchProcurementUseCase useCase = mock(MatchProcurementUseCase.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<String> traces = new ArrayList<>();
  private final List<String> spans = new ArrayList<>();
  private final List<String> closed = new ArrayList<>();

  private final ProcurementEventsForAlertsListener listener =
      new ProcurementEventsForAlertsListener(
          useCase,
          new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP) {
            @Override
            public Scope continueFrom(String traceparent, String name) {
              traces.add(traceparent);
              spans.add(name);
              return () -> closed.add(name);
            }
          },
          Clock.fixed(Facts.NOW, ZoneOffset.UTC),
          registry,
          Duration.ofDays(1));

  private static ConsumerRecord<String, String> record(String value) {
    return new ConsumerRecord<>("radar.procurement.v1", 5, 0L, Facts.ID, value);
  }

  private void send(String value) {
    listener.onMessage(record(value));
  }

  private static String occurringAt(String message, Instant at) {
    var tree = (ObjectNode) JSON.readTree(message);
    tree.put("occurredAt", at.toString());
    return JSON.writeValueAsString(tree);
  }

  private static ObjectNode snapshot() {
    return (ObjectNode)
        JSON.readTree(
            """
            {"id":"83021808000182-1-000001/2026","status":"PUBLISHED",
             "situation":{"id":1,"name":"Divulgada"},
             "organ":{"cnpj":"83021808000182","name":"Prefeitura de Exemplo"},
             "unit":{"code":"1010","name":"Secretaria de Compras","uf":"SC",
                     "municipalityName":"Exemplo","ibgeCode":"4205407"},
             "modality":{"id":6,"name":"Pregao Eletronico"},
             "disputeMode":null,"legalBasis":null,"srp":false,
             "estimatedValue":"1500.50","awardedValue":null,
             "proposalWindow":{"openingAt":"2026-10-23T12:00:00Z","closingAt":"2026-10-26T12:00:00Z"},
             "publishedAt":"2026-10-21T12:00:00Z","pncpUpdatedAt":"2026-10-22T10:00:00Z",
             "objectDescription":"Aquisicao de notebooks para a secretaria de educacao",
             "revision":1}
            """);
  }

  private static String envelope(
      String type, UUID eventId, Consumer<ObjectNode> payload, String traceparent) {
    var node = JSON.createObjectNode();
    node.put("eventId", eventId.toString());
    node.put("eventType", type);
    node.put("schemaVersion", 1);
    node.put("aggregateType", "Procurement");
    node.put("aggregateId", Facts.ID);
    node.put("aggregateVersion", 1);
    node.put("occurredAt", "2026-10-22T12:00:00Z");
    if (traceparent == null) {
      node.putNull("traceparent");
    } else {
      node.put("traceparent", traceparent);
    }
    var body = node.putObject("payload");
    body.put("aggregateId", Facts.ID);
    body.put("occurredAt", "2026-10-22T12:00:00Z");
    payload.accept(body);
    return JSON.writeValueAsString(node);
  }

  private static String discovered(UUID eventId, ObjectNode snapshot, String traceparent) {
    return envelope(
        "ProcurementDiscovered", eventId, p -> p.set("snapshot", snapshot), traceparent);
  }

  private static String updated(UUID eventId, String... changed) {
    return envelope(
        "ProcurementUpdated",
        eventId,
        p -> {
          p.set("snapshot", snapshot());
          var fields = p.putArray("changedFields");
          for (var field : changed) {
            fields.add(field);
          }
        },
        null);
  }

  private static ProcurementFacts sparseFacts() {
    return new ProcurementFacts(
        Facts.ID,
        "",
        "Prefeitura de Exemplo",
        "Secretaria de Compras",
        null,
        null,
        null,
        6,
        null,
        null);
  }

  @Test
  void theFixturesAreValidEnvelopesOfTheProducersContract() {
    var id = UUID.fromString("0198a3c2-4f10-7000-8000-000000000001");

    for (var message :
        List.of(
            discovered(id, snapshot(), TRACE),
            updated(id, "objectDescription"),
            envelope(
                "ProcurementClosed", id, p -> p.put("closingAt", "2026-10-26T12:00:00Z"), null))) {
      var type = JSON.readTree(message).get("eventType").asString();
      var schema = type.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
      assertThat(EventSchemas.load("envelope").validate(message, InputFormat.JSON)).isEmpty();
      assertThat(EventSchemas.load(schema + ".v1").validate(message, InputFormat.JSON))
          .as(type)
          .isEmpty();
    }
  }

  @Test
  void aDiscoveredEventIsMatchedWithTheFactsOfItsSnapshot() {
    var id = UUID.randomUUID();

    send(discovered(id, snapshot(), null));

    verify(useCase).onDiscovered(id, Facts.procurement().build());
  }

  @Test
  void aSnapshotWithoutOptionalFieldsBecomesSparseFacts() {
    var id = UUID.randomUUID();
    var bare = snapshot();
    bare.put("objectDescription", "");
    bare.putNull("estimatedValue");
    bare.putNull("proposalWindow");
    var unit = (ObjectNode) bare.get("unit");
    unit.putNull("uf");
    unit.putNull("municipalityName");
    unit.putNull("ibgeCode");

    send(discovered(id, bare, null));

    verify(useCase).onDiscovered(id, sparseFacts());
  }

  @Test
  void aProposalWindowWithoutClosingTimeLeavesTheClosingTimeEmpty() {
    var id = UUID.randomUUID();
    var open = snapshot();
    ((ObjectNode) open.get("proposalWindow")).putNull("closingAt");

    send(discovered(id, open, null));

    verify(useCase).onDiscovered(id, Facts.procurement().closingAt(null).build());
  }

  @Test
  void anUpdatedEventPassesItsChangedFields() {
    var id = UUID.randomUUID();

    send(updated(id, "estimatedValue", "situation"));

    verify(useCase)
        .onUpdated(id, Facts.procurement().build(), List.of("estimatedValue", "situation"));
  }

  @Test
  void anUpdatedEventWithoutChangedFieldsChangedNothingTheMatcherSees() {
    var id = UUID.randomUUID();

    send(envelope("ProcurementUpdated", id, p -> p.set("snapshot", snapshot()), null));

    verify(useCase).onUpdated(id, Facts.procurement().build(), List.of());
  }

  @Test
  void situationChangesAndClosuresAreAcknowledgedWithoutMatching() {
    var id = UUID.randomUUID();

    send(envelope("ProcurementSituationChanged", id, p -> p.put("unexpected", false), TRACE));
    send(envelope("ProcurementClosed", id, p -> p.put("closingAt", "2026-10-26T12:00:00Z"), TRACE));
    send(envelope("SomethingNew", id, p -> {}, TRACE));

    verifyNoInteractions(useCase);
    assertThat(traces).isEmpty();
  }

  @Test
  void theMatchRunsInsideASpanOfTheProducersTrace() {
    var id = UUID.randomUUID();
    var during = new ArrayList<List<String>>();
    org.mockito.Mockito.when(useCase.onDiscovered(any(), any()))
        .thenAnswer(
            call -> {
              during.add(List.copyOf(closed));
              return 1;
            });

    send(discovered(id, snapshot(), TRACE));

    assertThat(traces).containsExactly(TRACE);
    assertThat(spans).containsExactly("match procurement");
    assertThat(during).containsExactly(List.of());
    assertThat(closed).containsExactly("match procurement");
  }

  @Test
  void aMissingTraceparentStillMatches() {
    send(discovered(UUID.randomUUID(), snapshot(), null));

    assertThat(traces).containsExactly((String) null);
    verify(useCase).onDiscovered(any(), any());
  }

  @Test
  void aMessageThatIsNotJsonFailsSoTheErrorHandlerSeesIt() {
    assertThatThrownBy(() -> send("not json")).isInstanceOf(RuntimeException.class);
    verifyNoInteractions(useCase);
  }

  @Test
  void aMatchableEventWithoutSnapshotFails() {
    var message =
        envelope("ProcurementDiscovered", UUID.randomUUID(), p -> p.put("note", "x"), null);

    assertThatThrownBy(() -> send(message))
        .isInstanceOf(MalformedEventException.class)
        .hasMessageContaining("snapshot");
    verifyNoInteractions(useCase);
  }

  @Test
  void aSnapshotWithoutItsOrganOrUnitOrModalityIsMalformed() {
    for (var part : List.of("organ", "unit", "modality")) {
      var broken = snapshot();
      broken.remove(part);

      assertThatThrownBy(() -> send(discovered(UUID.randomUUID(), broken, null)))
          .as(part)
          .isInstanceOf(MalformedEventException.class);
    }
    verifyNoInteractions(useCase);
  }

  @Test
  void aSnapshotWhoseOrganHasNoNameIsMalformed() {
    var broken = snapshot();
    ((ObjectNode) broken.get("organ")).remove("name");

    assertThatThrownBy(() -> send(discovered(UUID.randomUUID(), broken, null)))
        .isInstanceOf(MalformedEventException.class);
  }

  @Test
  void anEventExactlyAtTheMaximumAgeIsStillMatched() {
    var id = UUID.randomUUID();

    send(occurringAt(discovered(id, snapshot(), null), Facts.NOW.minus(Duration.ofDays(1))));

    verify(useCase).onDiscovered(id, Facts.procurement().build());
  }

  @Test
  void anEventOlderThanTheMaximumAgeIsAcknowledgedWithoutWork() {
    var old = Facts.NOW.minus(Duration.ofDays(1)).minusSeconds(1);

    send(occurringAt(discovered(UUID.randomUUID(), snapshot(), TRACE), old));
    send(occurringAt(updated(UUID.randomUUID(), "objectDescription"), old));

    verifyNoInteractions(useCase);
    assertThat(traces).isEmpty();
    assertThat(registry.get("alerts_stale_events_skipped_total").counter().count()).isEqualTo(2);
  }

  @Test
  void theStaleCounterExistsFromTheStart() {
    assertThat(registry.get("alerts_stale_events_skipped_total").counter().count()).isZero();
  }

  @Test
  void anOldEventTheOperatorPutBackFromTheDeadLettersIsMatchedNotSkipped() {
    var id = UUID.randomUUID();
    var old = Facts.NOW.minus(Duration.ofDays(3));
    var record = record(occurringAt(discovered(id, snapshot(), null), old));
    record
        .headers()
        .add("x-reprocessed-from", "dlq".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    listener.onMessage(record);

    verify(useCase).onDiscovered(id, Facts.procurement().build());
    assertThat(registry.get("alerts_stale_events_skipped_total").counter().count()).isZero();
  }

  @Test
  void theProducersKafkaHeaderWinsOverTheEnvelopeTraceparent() {
    var header = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    var record = record(discovered(UUID.randomUUID(), snapshot(), TRACE));
    record.headers().add("traceparent", header.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    listener.onMessage(record);

    assertThat(traces).containsExactly(header);
  }

  @Test
  void theEnvelopeTraceparentIsTheFallbackWhenTheRecordHasNoHeader() {
    send(discovered(UUID.randomUUID(), snapshot(), TRACE));

    assertThat(traces).containsExactly(TRACE);
  }

  @Test
  void anEnvelopeWithoutOccurredAtIsMalformed() {
    var tree = (ObjectNode) JSON.readTree(discovered(UUID.randomUUID(), snapshot(), null));
    tree.remove("occurredAt");

    assertThatThrownBy(() -> send(JSON.writeValueAsString(tree)))
        .isInstanceOf(MalformedEventException.class);
  }
}
