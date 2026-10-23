package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.kiota.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EnvelopeSerializerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Object> AVRO =
      Map.of(WireFormat.FORMAT, "avro", "apicurio.registry.url", "http://registry.invalid");

  private final InMemoryRegistry registry = new InMemoryRegistry();
  private final EnvelopeSerializer serializer = new EnvelopeSerializer(registry);

  static java.util.stream.Stream<Map.Entry<String, String>> samples() {
    return EventSamples.all().entrySet().stream();
  }

  private byte[] avro(String envelope, RecordHeaders headers) {
    serializer.configure(AVRO, false);
    return serializer.serialize(AvroWire.topicOf(envelope), headers, envelope);
  }

  @Test
  void jsonModeWritesTheTextAsUtf8() {
    serializer.configure(Map.of(), false);
    var text = "{\"nome\":\"licitação\"}";

    assertThat(serializer.serialize("radar.procurement.v1", text))
        .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    assertThat(registry.registrations()).isEmpty();
  }

  @Test
  void anyOtherFormatValueIsJsonMode() {
    serializer.configure(Map.of(WireFormat.FORMAT, "json"), false);

    assertThat(serializer.serialize("radar.procurement.v1", new RecordHeaders(), "{}"))
        .isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aNullValueStaysNull() {
    serializer.configure(AVRO, false);

    assertThat(serializer.serialize("radar.procurement.v1", null)).isNull();
  }

  @Test
  void deadLetterTopicsStayTextInAvroMode() {
    serializer.configure(AVRO, false);
    var headers = new RecordHeaders();

    var bytes = serializer.serialize("radar.procurement.v1.dlq", headers, "{not json");

    assertThat(bytes).isEqualTo("{not json".getBytes(StandardCharsets.UTF_8));
    assertThat(headers.lastHeader(WireFormat.CONTENT_TYPE)).isNull();
  }

  @ParameterizedTest
  @MethodSource("samples")
  void avroModeWritesTheConfluentFraming(Map.Entry<String, String> sample) {
    var headers = new RecordHeaders();

    var bytes = avro(sample.getValue(), headers);

    var framing = ByteBuffer.wrap(bytes);
    // magic byte and a 4-byte big-endian schema id, the layout the Confluent serdes read
    assertThat(framing.get()).isZero();
    assertThat(framing.getInt()).isEqualTo(1);
    assertThat(new String(headers.lastHeader(WireFormat.CONTENT_TYPE).value()))
        .isEqualTo("application/avro");
  }

  @ParameterizedTest
  @MethodSource("samples")
  void eachEventIsRegisteredInTheGroupOfItsTopicUnderItsRecordName(
      Map.Entry<String, String> sample) {
    avro(sample.getValue(), new RecordHeaders());

    var registration = registry.registrations().get(0);
    assertThat(registration.groupId()).isEqualTo(AvroWire.topicOf(sample.getValue()));
    assertThat(registration.artifactId()).isEqualTo(sample.getKey());
    assertThat(registration.content()).contains("\"name\":\"" + sample.getKey() + "\"");
  }

  @Test
  void aFieldThatIsAbsentButHasADefaultIsWrittenWithTheDefault() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.closed());
    envelope.remove("traceparent");
    var wire = new AvroWire();
    var headers = new RecordHeaders();

    var decoded =
        wire.decode("radar.procurement.v1", headers, wire.encode(envelope.toString(), headers));

    assertThat(JSON.readTree(decoded).get("traceparent").isNull()).isTrue();
  }

  @Test
  void aRequiredFieldThatIsAbsentIsASerializationErrorThatNamesIt() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.closed());
    ((ObjectNode) envelope.get("payload")).remove("closingAt");

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class)
        .hasRootCauseMessage("missing field closingAt");
  }

  @Test
  void aNumberWhereTheSchemaWantsATextIsASerializationError() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.closed());
    envelope.put("eventId", 7);

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class);
  }

  @Test
  void aTextWhereTheSchemaWantsANumberIsASerializationError() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.closed());
    envelope.put("aggregateVersion", "3");

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class)
        .hasRootCauseMessage("not an integer: aggregateVersion");
  }

  @Test
  void aNumberThatDoesNotFitAnIntIsASerializationError() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.closed());
    envelope.put("schemaVersion", 3_000_000_000L);

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class)
        .hasRootCauseMessage("not an integer: schemaVersion");
  }

  @Test
  void anObjectWhereTheSchemaWantsAListIsASerializationError() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.updated());
    ((ObjectNode) envelope.get("payload")).putObject("changedFields");

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class)
        .hasRootCauseMessage("not a list: changedFields");
  }

  @Test
  void aTextWhereTheSchemaWantsABooleanIsASerializationError() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.situationChanged());
    ((ObjectNode) envelope.get("payload")).put("unexpected", "yes");

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class)
        .hasRootCauseMessage("not a boolean: unexpected");
  }

  @Test
  void anEventTypeWithoutASchemaIsASerializationError() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.closed());
    envelope.put("eventType", "Nope");

    assertThatThrownBy(() -> avro(envelope.toString(), new RecordHeaders()))
        .isInstanceOf(SerializationException.class)
        .hasRootCauseMessage("no avro schema for event type Nope");
  }

  @Test
  void textThatIsNotJsonIsASerializationError() {
    serializer.configure(AVRO, false);

    assertThatThrownBy(
            () -> serializer.serialize("radar.procurement.v1", new RecordHeaders(), "{not json"))
        .isInstanceOf(SerializationException.class);
  }

  @Test
  void anUnreachableRegistryIsRetriableSoTheRowsWaitInsteadOfBurningAttempts() {
    var down = new EnvelopeSerializer(new InMemoryRegistry().down());
    down.configure(AVRO, false);

    assertThatThrownBy(
            () ->
                down.serialize("radar.procurement.v1", new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(RetriableException.class)
        .hasMessageContaining("schema registry")
        .hasRootCauseMessage("registry unreachable");
  }

  @Test
  void theDefaultsAreTheConfluentCompatibleIdAndTheTopicGroup() {
    var config = WireFormat.registryConfig(Map.of("apicurio.registry.url", "http://r"));

    assertThat(config)
        .containsEntry("apicurio.registry.url", "http://r")
        .containsEntry(
            "apicurio.registry.id-handler", "io.apicurio.registry.serde.Default4ByteIdHandler")
        .containsEntry("apicurio.registry.use-id", "contentId")
        .containsEntry("apicurio.registry.auto-register", "true")
        .containsEntry("apicurio.registry.dereference-schema", "true")
        .containsEntry("apicurio.registry.http.adapter", "JDK")
        .containsEntry(
            "apicurio.registry.artifact-resolver-strategy",
            TopicGroupRecordIdStrategy.class.getName());
  }

  @Test
  void theConfiguredValuesWinOverTheDefaults() {
    var config = WireFormat.registryConfig(Map.of("apicurio.registry.auto-register", "false"));

    assertThat(config).containsEntry("apicurio.registry.auto-register", "false");
  }

  @Test
  void theKafkaPathBuildsTheRegistryClientAndClosesIt() {
    var real = new EnvelopeSerializer();

    assertThatCode(
            () -> {
              real.configure(AVRO, false);
              real.close();
            })
        .doesNotThrowAnyException();
  }

  @Test
  void closingInJsonModeIsHarmless() {
    serializer.configure(Map.of(), false);

    assertThatCode(serializer::close).doesNotThrowAnyException();
  }

  @Test
  void aDeadLetterRecordDoesNotKeepTheAvroContentTypeItInheritedFromItsOrigin() {
    serializer.configure(AVRO, false);
    var headers =
        new RecordHeaders()
            .add(WireFormat.CONTENT_TYPE, WireFormat.AVRO.getBytes(StandardCharsets.UTF_8))
            .add("x-original-topic", "radar.procurement.v1".getBytes(StandardCharsets.UTF_8));

    serializer.serialize("radar.procurement.v1.dlq", headers, "{\"texto\":true}");

    assertThat(headers.lastHeader(WireFormat.CONTENT_TYPE)).isNull();
    assertThat(headers.lastHeader("x-original-topic")).isNotNull();
  }

  private EnvelopeSerializer serializerOver(InMemoryRegistry broken, Map<String, Object> more) {
    var configured = new EnvelopeSerializer(broken);
    var config = new java.util.HashMap<String, Object>(AVRO);
    config.putAll(more);
    configured.configure(config, false);
    return configured;
  }

  private static ApiException status(int code) {
    return new ApiException("registry answered " + code) {
      {
        setResponseStatusCode(code);
      }
    };
  }

  @Test
  void aRegistryThatAnswersWithAServerErrorIsRetriable() {
    var broken = serializerOver(new InMemoryRegistry().failingWith(status(503)), Map.of());

    assertThatThrownBy(
            () ->
                broken.serialize(
                    "radar.procurement.v1", new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(RetriableException.class);
  }

  @Test
  void aRegistryThatRefusesTheSchemaIsASerializationErrorThatCountsAnAttempt() {
    var broken = serializerOver(new InMemoryRegistry().failingWith(status(400)), Map.of());

    assertThatThrownBy(
            () ->
                broken.serialize(
                    "radar.procurement.v1", new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(SerializationException.class)
        .isNotInstanceOf(RetriableException.class)
        .hasMessageContaining("registry answered 400");
  }

  @Test
  void anAvroErrorOfTheEncoderIsASerializationError() {
    var broken =
        serializerOver(
            new InMemoryRegistry()
                .failingWith(new org.apache.avro.AvroRuntimeException("bad datum")),
            Map.of());

    assertThatThrownBy(
            () ->
                broken.serialize(
                    "radar.procurement.v1", new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(SerializationException.class)
        .isNotInstanceOf(RetriableException.class);
  }

  @Test
  void aRegistryThatNeverAnswersIsRetriableAfterTheConfiguredBound() {
    var silent =
        serializerOver(
            new InMemoryRegistry().hanging(), Map.of(WireFormat.REGISTRY_TIMEOUT_MS, "200"));
    var start = System.nanoTime();

    assertThatThrownBy(
            () ->
                silent.serialize(
                    "radar.procurement.v1", new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(RetriableException.class);
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
        .isLessThan(java.time.Duration.ofSeconds(5));
  }

  @Test
  void theFormatIsReadIgnoringCase() {
    serializer.configure(
        Map.of(WireFormat.FORMAT, "AVRO", "apicurio.registry.url", "http://r"), false);

    var headers = new RecordHeaders();
    serializer.serialize("radar.procurement.v1", headers, EventSamples.closed());

    assertThat(headers.lastHeader(WireFormat.CONTENT_TYPE)).isNotNull();
  }

  @Test
  void aRegistryThatRefusesTheConnectionIsRetriableThroughTheRealClient() {
    var real = new EnvelopeSerializer();
    real.configure(
        Map.of(WireFormat.FORMAT, "avro", "apicurio.registry.url", "http://127.0.0.1:1"), false);

    assertThatThrownBy(
            () ->
                real.serialize("radar.procurement.v1", new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(RetriableException.class);
    real.close();
  }
}
