package br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.T0;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.bare;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.clockAt;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementEvent;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.observability.TraceparentInjector;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaEventPublisher;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaPublisherProperties;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import com.networknt.schema.InputFormat;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pushes real domain events through the production path (JdbcOutboxAppender serialization, then the
 * row to envelope assembly of KafkaEventPublisher) and validates the bytes sent to Kafka against
 * the schemas shipped in the contracts module.
 */
class EventContractTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TraceparentInjector NO_TRACE =
      new TraceparentInjector(Tracer.NOOP, Propagator.NOOP);
  private static final TypeReference<Map<String, Object>> HEADERS = new TypeReference<>() {};

  /** Captures the INSERT parameters instead of talking to a database. */
  private static final class RecordingJdbc extends JdbcTemplate {
    final List<Object[]> rows = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      rows.add(args);
      return 1;
    }
  }

  private static List<String> publishedEnvelopes(
      long aggregateVersion, List<ProcurementEvent> events) throws Exception {
    return publishedEnvelopes(aggregateVersion, events, NO_TRACE);
  }

  private static List<String> publishedEnvelopes(
      long aggregateVersion, List<ProcurementEvent> events, TraceparentInjector traces)
      throws Exception {
    var jdbc = new RecordingJdbc();
    new JdbcOutboxAppender(jdbc, new UuidV7(clockAt(T0), new SecureRandom()), traces)
        .append("Procurement", aggregateVersion, events);

    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
    var publisher =
        new KafkaEventPublisher(
            template,
            new KafkaPublisherProperties(
                Map.of("Procurement", "radar.procurement.v1"), Duration.ofSeconds(1), 1),
            new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP));
    for (var row : jdbc.rows) {
      Map<String, Object> headers = JSON.readValue((String) row[6], HEADERS);
      publisher.publish(
          new OutboxMessage(
              1,
              (UUID) row[0],
              (String) row[1],
              (String) row[2],
              (String) row[3],
              (Integer) row[4],
              (String) row[5],
              headers,
              T0,
              0));
    }
    var captor = ArgumentCaptor.<ProducerRecord<String, String>>captor();
    verify(template, times(jdbc.rows.size())).send(captor.capture());
    return captor.getAllValues().stream().map(ProducerRecord::value).toList();
  }

  private static String schemaName(String eventType) {
    return eventType.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT) + ".v1";
  }

  private static Stream<Arguments> cases() {
    var clock = clockAt(T0);
    var full = Procurement.discover(snapshot(id(1)), clock);
    var sparse = Procurement.discover(bare(id(2)), clock);

    var changing = Procurement.discover(snapshot(id(3)), clock).procurement();
    PncpSnapshot revised = snapshot(id(3), 2, "Aquisicao de material de limpeza");
    var updateAndSituation = changing.applySnapshot(revised, clockAt(T0.plusSeconds(60)));

    var closing = Procurement.discover(snapshot(id(4)), clock).procurement();
    var closed = closing.closeIfExpired(clockAt(T0.plus(Duration.ofDays(6)))).orElseThrow();

    var plain = Procurement.discover(snapshot(id(5)), clock).procurement();
    var textOnly =
        plain.applySnapshot(
            snapshot(id(5), 1, "Aquisicao de material de escritorio e papel"),
            clockAt(T0.plusSeconds(60)));

    return Stream.of(
        Arguments.of("discovered with every field", 1L, full.events()),
        Arguments.of("discovered with only the required fields", 1L, sparse.events()),
        Arguments.of("updated and situation changed", 2L, updateAndSituation),
        Arguments.of("updated without a situation change", 2L, textOnly),
        Arguments.of("closed", 2L, List.<ProcurementEvent>of(closed)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void everyPublishedEnvelopeMatchesItsSchema(
      String label, long aggregateVersion, List<ProcurementEvent> events) throws Exception {
    var envelopes = publishedEnvelopes(aggregateVersion, events);

    assertThat(envelopes).hasSameSizeAs(events);
    for (var i = 0; i < events.size(); i++) {
      var type = events.get(i).eventType();
      assertThat(EventSchemas.load("envelope").validate(envelopes.get(i), InputFormat.JSON))
          .as("envelope of %s", type)
          .isEmpty();
      assertThat(EventSchemas.load(schemaName(type)).validate(envelopes.get(i), InputFormat.JSON))
          .as("%s against its schema", type)
          .isEmpty();
    }
  }

  @Test
  void aTraceparentIsCarriedAsAW3cString() throws Exception {
    var traced =
        new TraceparentInjector(Tracer.NOOP, Propagator.NOOP) {
          @Override
          public String current() {
            return "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
          }
        };

    var envelope = publishedEnvelopes(2, List.of(closedEvent()), traced).getFirst();

    assertThat(JSON.readTree(envelope).get("traceparent").asString())
        .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    assertThat(EventSchemas.load("procurement-closed.v1").validate(envelope, InputFormat.JSON))
        .isEmpty();
  }

  @Test
  void aSchemaRejectsAnEnvelopeOfAnotherEventType() throws Exception {
    var closed = publishedEnvelopes(2, List.of(closedEvent())).getFirst();

    assertThat(EventSchemas.load("procurement-discovered.v1").validate(closed, InputFormat.JSON))
        .isNotEmpty();
  }

  @Test
  void aMissingRequiredPayloadFieldFails() throws Exception {
    var envelope = JSON.readTree(publishedEnvelopes(2, List.of(closedEvent())).getFirst());
    ((ObjectNode) envelope.get("payload")).remove("closingAt");

    assertThat(
            EventSchemas.load("procurement-closed.v1")
                .validate(JSON.writeValueAsString(envelope), InputFormat.JSON))
        .isNotEmpty();
  }

  @Test
  void anUnknownPayloadFieldFails() throws Exception {
    var envelope = JSON.readTree(publishedEnvelopes(2, List.of(closedEvent())).getFirst());
    ((ObjectNode) envelope.get("payload")).put("surprise", true);

    assertThat(
            EventSchemas.load("procurement-closed.v1")
                .validate(JSON.writeValueAsString(envelope), InputFormat.JSON))
        .isNotEmpty();
  }

  @Test
  void aTimestampThatIsNotADateTimeFails() throws Exception {
    var envelope = JSON.readTree(publishedEnvelopes(2, List.of(closedEvent())).getFirst());
    ((ObjectNode) envelope.get("payload")).put("closingAt", "tomorrow");

    assertThat(
            EventSchemas.load("procurement-closed.v1")
                .validate(JSON.writeValueAsString(envelope), InputFormat.JSON))
        .isNotEmpty();
  }

  @Test
  void aMissingEnvelopeFieldFails() throws Exception {
    var envelope = JSON.readTree(publishedEnvelopes(2, List.of(closedEvent())).getFirst());
    ((ObjectNode) envelope).remove("aggregateVersion");

    assertThat(
            EventSchemas.load("envelope")
                .validate(JSON.writeValueAsString(envelope), InputFormat.JSON))
        .isNotEmpty();
  }

  @Test
  void aNullableFieldMayBeNullButARequiredOneMayNot() throws Exception {
    var envelope = JSON.readTree(publishedEnvelopes(1, bareDiscovered()).getFirst());
    JsonNode snapshot = envelope.get("payload").get("snapshot");
    assertThat(snapshot.get("estimatedValue").isNull()).isTrue();

    ((ObjectNode) snapshot).putNull("objectDescription");

    assertThat(
            EventSchemas.load("procurement-discovered.v1")
                .validate(JSON.writeValueAsString(envelope), InputFormat.JSON))
        .isNotEmpty();
  }

  private static ProcurementEvent closedEvent() {
    var procurement = Procurement.discover(snapshot(id(4)), clockAt(T0)).procurement();
    return procurement.closeIfExpired(clockAt(Instant.parse("2026-11-30T00:00:00Z"))).orElseThrow();
  }

  private static List<ProcurementEvent> bareDiscovered() {
    return Procurement.discover(bare(id(2)), clockAt(T0)).events();
  }
}
