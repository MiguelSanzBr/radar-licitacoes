package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.TRACEPARENT;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.closed;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.envelope;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.updated;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EnvelopeParserTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final EnvelopeParser parser = new EnvelopeParser();

  static Stream<ProcurementEvent> events() {
    var view = view(id(1), "Contratação de serviços", "120000.00", 1);
    return Stream.of(
        discovered("d", view, 1),
        updated(
            "u",
            view(id(1), "Contratação de serviços", "130000.00", 4, 2, view.pncpUpdatedAt()),
            2),
        situationChanged("s", id(1), 2, 1, 4),
        closed("c", id(1), 3));
  }

  @ParameterizedTest
  @MethodSource("events")
  void everyKnownEventParsesBackToTheEventThatWasWritten(ProcurementEvent event) {
    var parsed = parser.parse(envelope(event)).orElseThrow();

    assertThat(parsed.event()).isEqualTo(event);
    assertThat(parsed.traceparent()).isEqualTo(TRACEPARENT);
  }

  @Test
  void aSnapshotWithoutLocationOrWindowOrValueParsesWithNulls() throws Exception {
    var event = discovered("d", view(id(2), "object", null, 1), 1);
    var envelope = (ObjectNode) JSON.readTree(envelope(event));
    var snapshot = (ObjectNode) envelope.get("payload").get("snapshot");
    var unit = (ObjectNode) snapshot.get("unit");
    unit.putNull("uf").putNull("municipalityName").putNull("ibgeCode");
    snapshot.putNull("proposalWindow");
    envelope.putNull("traceparent");

    var parsed = parser.parse(envelope.toString()).orElseThrow();

    var view = ((ProcurementEvent.Discovered) parsed.event()).snapshot();
    assertThat(view.uf()).isNull();
    assertThat(view.municipalityName()).isNull();
    assertThat(view.ibgeCode()).isNull();
    assertThat(view.proposalOpeningAt()).isNull();
    assertThat(view.proposalClosingAt()).isNull();
    assertThat(view.estimatedValue()).isNull();
    assertThat(parsed.traceparent()).isNull();
  }

  @Test
  void aWindowWithOneBoundParsesTheOtherAsNull() throws Exception {
    var event = discovered("d", view(id(2), "object", null, 1), 1);
    var envelope = (ObjectNode) JSON.readTree(envelope(event));
    var window = (ObjectNode) envelope.get("payload").get("snapshot").get("proposalWindow");
    window.putNull("openingAt");

    var view =
        ((ProcurementEvent.Discovered) parser.parse(envelope.toString()).orElseThrow().event())
            .snapshot();

    assertThat(view.proposalOpeningAt()).isNull();
    assertThat(view.proposalClosingAt()).isNotNull();
  }

  @Test
  void anUnknownEventTypeIsIgnored() throws Exception {
    var envelope = (ObjectNode) JSON.readTree(envelope(closed("c", id(1), 3)));
    envelope.put("eventType", "AlertMatched");

    assertThat(parser.parse(envelope.toString())).isEmpty();
  }

  @Test
  void fieldsThisVersionDoesNotKnowAreIgnored() throws Exception {
    var envelope = (ObjectNode) JSON.readTree(envelope(closed("c", id(1), 3)));
    envelope.put("addedLater", true);
    ((ObjectNode) envelope.get("payload")).put("alsoLater", 1);

    assertThat(parser.parse(envelope.toString())).isPresent();
  }

  @Test
  void malformedJsonThrows() {
    assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(JacksonException.class);
  }

  @Test
  void anEnvelopeWithoutAnEventIdThrows() throws Exception {
    var envelope = (ObjectNode) JSON.readTree(envelope(closed("c", id(1), 3)));
    envelope.remove("eventId");

    assertThatThrownBy(() -> parser.parse(envelope.toString()))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aMissingAggregateVersionIsPoisonNotZero() throws Exception {
    var envelope = (ObjectNode) JSON.readTree(envelope(closed("c", id(1), 3)));
    envelope.remove("aggregateVersion");

    assertThatThrownBy(() -> parser.parse(envelope.toString()))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aMissingPayloadFieldIsPoison() throws Exception {
    var envelope = (ObjectNode) JSON.readTree(envelope(situationChanged("s", id(1), 2, 1, 4)));
    ((ObjectNode) envelope.get("payload")).remove("unexpected");

    assertThatThrownBy(() -> parser.parse(envelope.toString()))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aNullRequiredSnapshotFieldIsPoison() throws Exception {
    var envelope =
        (ObjectNode) JSON.readTree(envelope(discovered("d", view(id(1), "object", null, 1), 1)));
    ((ObjectNode) envelope.get("payload").get("snapshot")).putNull("publishedAt");

    assertThatThrownBy(() -> parser.parse(envelope.toString()))
        .isInstanceOf(RuntimeException.class);
  }
}
