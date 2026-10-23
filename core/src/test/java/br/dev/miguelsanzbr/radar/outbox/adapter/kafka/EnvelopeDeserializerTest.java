package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EnvelopeDeserializerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TOPIC = "radar.procurement.v1";

  private final AvroWire wire = new AvroWire();
  private final EnvelopeDeserializer json = new EnvelopeDeserializer(new InMemoryRegistry());

  static Stream<String> envelopes() {
    return EventSamples.all().values().stream();
  }

  @ParameterizedTest
  @MethodSource("envelopes")
  void everyEventReadsBackAsTheJsonItWasWrittenFrom(String envelope) {
    assertThat(JSON.readTree(wire.roundTrip(envelope))).isEqualTo(JSON.readTree(envelope));
  }

  @Test
  void anOptionalFieldThatWasNullReadsBackAsNull() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.discovered());
    var snapshot = (ObjectNode) envelope.get("payload").get("snapshot");
    snapshot.putNull("disputeMode").putNull("legalBasis").putNull("proposalWindow");
    ((ObjectNode) snapshot.get("unit")).putNull("uf").putNull("ibgeCode");
    envelope.putNull("traceparent");

    assertThat(JSON.readTree(wire.roundTrip(envelope.toString()))).isEqualTo(envelope);
  }

  @Test
  void textWithQuotesBackslashesAndAccentsSurvivesAsJson() {
    var envelope = (ObjectNode) JSON.readTree(EventSamples.alertMatched());
    ((ObjectNode) envelope.get("payload"))
        .put("objectDescription", "Aquisição \"urgente\" de C:\\dados\ne mais\tuma linha");

    assertThat(JSON.readTree(wire.roundTrip(envelope.toString()))).isEqualTo(envelope);
  }

  @Test
  void jsonRecordsPassThroughAsTheyAre() {
    var text = EventSamples.closed();

    assertThat(json.deserialize(TOPIC, new RecordHeaders(), text.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(text);
  }

  @Test
  void aNullValueStaysNull() {
    assertThat(json.deserialize(TOPIC, new RecordHeaders(), (byte[]) null)).isNull();
    assertThat(json.deserialize(TOPIC, (byte[]) null)).isNull();
  }

  @Test
  void theContentTypeHeaderDecidesWhenTheProducerSetsIt() {
    var headers = new RecordHeaders();
    var bytes = wire.encode(EventSamples.closed(), headers);

    assertThat(headers.lastHeader(WireFormat.CONTENT_TYPE)).isNotNull();
    assertThat(JSON.readTree(wire.decode(TOPIC, headers, bytes)))
        .isEqualTo(JSON.readTree(EventSamples.closed()));
  }

  @Test
  void withoutTheHeaderTheMagicByteDecides() {
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    assertThat(bytes[0]).isZero();
    assertThat(JSON.readTree(wire.decode(TOPIC, new RecordHeaders(), bytes)))
        .isEqualTo(JSON.readTree(EventSamples.closed()));
  }

  @Test
  void aJsonContentTypeKeepsTheValueAsText() {
    var headers = new RecordHeaders().add(WireFormat.CONTENT_TYPE, "application/json".getBytes());

    assertThat(json.deserialize(TOPIC, headers, "{}".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("{}");
  }

  @Test
  void anEmptyValueWithoutHeaderIsAnEmptyText() {
    assertThat(json.deserialize(TOPIC, new RecordHeaders(), new byte[0])).isEmpty();
  }

  @Test
  void avroThatCannotBeDecodedBecomesTextThatFailsAsJsonAndKeepsTheBytes() {
    var headers = new RecordHeaders();
    var bytes = wire.encode(EventSamples.closed(), headers);
    var other = new AvroWire();

    // the registry of this wire never saw the schema id inside the bytes
    var text = other.decode(TOPIC, headers, bytes);

    assertThat(text).startsWith(WireFormat.UNDECODABLE);
    assertThat(Base64.getDecoder().decode(text.substring(WireFormat.UNDECODABLE.length())))
        .isEqualTo(bytes);
    assertThatCode(() -> JSON.readTree(text))
        .isInstanceOf(tools.jackson.core.JacksonException.class);
  }

  @Test
  void aRegistryThatIsDownIsMarkedForARetryThroughTheWire() {
    var down = new AvroWire(new InMemoryRegistry().down());
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    assertThat(down.decode(TOPIC, new RecordHeaders(), bytes))
        .startsWith(RegistryUnavailableException.MARKER);
  }

  @Test
  void theKafkaPathBuildsTheRegistryClientOnTheFirstAvroRecordAndClosesIt() {
    var real = new EnvelopeDeserializer();
    real.configure(Map.of("apicurio.registry.url", "http://127.0.0.1:1"), false);
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    // nothing listens there: the record is marked for a retry, never an exception to the consumer
    assertThat(real.deserialize(TOPIC, new RecordHeaders(), bytes))
        .startsWith(RegistryUnavailableException.MARKER);
    assertThatCode(real::close).doesNotThrowAnyException();
  }

  @Test
  void closingBeforeAnyAvroRecordIsHarmless() {
    json.configure(Map.of(), false);

    assertThatCode(json::close).doesNotThrowAnyException();
  }

  @Test
  void aJsonTextKeepsBeingTextEvenWithTheAvroContentTypeOfItsOrigin() {
    var headers =
        new RecordHeaders()
            .add(WireFormat.CONTENT_TYPE, WireFormat.AVRO.getBytes(StandardCharsets.UTF_8));
    var text = EventSamples.closed();

    assertThat(json.deserialize(TOPIC, headers, text.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(text);
  }

  @Test
  void anEmptyValueWithTheAvroContentTypeIsAnEmptyText() {
    var headers =
        new RecordHeaders()
            .add(WireFormat.CONTENT_TYPE, WireFormat.AVRO.getBytes(StandardCharsets.UTF_8));

    assertThat(json.deserialize(TOPIC, headers, new byte[0])).isEmpty();
  }

  private static com.microsoft.kiota.ApiException status(int code) {
    return new com.microsoft.kiota.ApiException("registry answered " + code) {
      {
        setResponseStatusCode(code);
      }
    };
  }

  private String decodeWithRegistryFailure(InMemoryRegistry broken) {
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());
    return new AvroWire(broken).decode(TOPIC, new RecordHeaders(), bytes);
  }

  @Test
  void aRegistryThatCannotBeReachedLeavesTheRecordMarkedForARetryWithItsBytes() {
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    var text =
        new AvroWire(new InMemoryRegistry().down()).decode(TOPIC, new RecordHeaders(), bytes);

    assertThat(text).startsWith(RegistryUnavailableException.MARKER);
    assertThat(
            Base64.getDecoder()
                .decode(text.substring(RegistryUnavailableException.MARKER.length())))
        .isEqualTo(bytes);
  }

  @Test
  void aRegistryServerErrorIsMarkedForARetry() {
    assertThat(decodeWithRegistryFailure(new InMemoryRegistry().failingWith(status(503))))
        .startsWith(RegistryUnavailableException.MARKER);
  }

  @Test
  void aSchemaTheRegistryDoesNotKnowIsPoisonNotARetry() {
    assertThat(decodeWithRegistryFailure(new InMemoryRegistry().failingWith(status(404))))
        .startsWith(WireFormat.UNDECODABLE);
  }

  @Test
  void aRegistryThatNeverAnswersIsMarkedForARetryAtTheBound() {
    var silent =
        new AvroWire(
            new InMemoryRegistry().hanging(), Map.of(WireFormat.REGISTRY_TIMEOUT_MS, "200"));
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    assertThat(silent.decode(TOPIC, new RecordHeaders(), bytes))
        .startsWith(RegistryUnavailableException.MARKER);
  }

  @Test
  void theSameDeserializerDecodesRecordAfterRecord() {
    var first = wire.roundTrip(EventSamples.closed());
    var second = wire.roundTrip(EventSamples.discovered());

    assertThat(JSON.readTree(first)).isEqualTo(JSON.readTree(EventSamples.closed()));
    assertThat(JSON.readTree(second)).isEqualTo(JSON.readTree(EventSamples.discovered()));
  }

  @Test
  void aContentTypeOfAnotherTypeKeepsEvenAMagicByteValueAsText() {
    var headers =
        new RecordHeaders()
            .add(WireFormat.CONTENT_TYPE, "text/plain".getBytes(StandardCharsets.UTF_8));

    assertThat(json.deserialize(TOPIC, headers, new byte[] {0, 65})).isEqualTo("\u0000A");
  }

  @Test
  void aKnownSchemaIdWithATruncatedBodyIsPoisonNotARetry() {
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());
    var truncated = java.util.Arrays.copyOf(bytes, 12);

    assertThat(wire.decode(TOPIC, new RecordHeaders(), truncated))
        .startsWith(WireFormat.UNDECODABLE);
  }

  @Test
  void aKnownSchemaIdWithGarbageAfterItIsPoisonNotARetry() {
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());
    var garbled = java.util.Arrays.copyOf(bytes, bytes.length);
    java.util.Arrays.fill(garbled, 5, garbled.length, (byte) 0xFF);

    assertThat(wire.decode(TOPIC, new RecordHeaders(), garbled)).startsWith(WireFormat.UNDECODABLE);
  }

  @Test
  void afterARegistryFailureTheNextRecordsAreMarkedAtOnceWithoutCallingTheRegistry() {
    var down = new InMemoryRegistry().down();
    var slow = new AvroWire(down, Map.of(WireFormat.REGISTRY_BACKOFF_MS, "60000"));
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    var first = slow.decode(TOPIC, new RecordHeaders(), bytes);
    var callsAfterTheFirst = down.calls();
    var second = slow.decode(TOPIC, new RecordHeaders(), bytes);
    var third = slow.decode(TOPIC, new RecordHeaders(), bytes);

    assertThat(callsAfterTheFirst).isPositive();
    assertThat(down.calls()).isEqualTo(callsAfterTheFirst);
    assertThat(List.of(first, second, third))
        .allSatisfy(text -> assertThat(text).startsWith(RegistryUnavailableException.MARKER));
    assertThat(second).isEqualTo(first);
  }

  @Test
  void whenTheWindowEndsTheRegistryIsAskedAgain() throws Exception {
    var down = new InMemoryRegistry().down();
    var short_ = new AvroWire(down, Map.of(WireFormat.REGISTRY_BACKOFF_MS, "50"));
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());
    short_.decode(TOPIC, new RecordHeaders(), bytes);
    var calls = down.calls();

    Thread.sleep(120);
    short_.decode(TOPIC, new RecordHeaders(), bytes);

    assertThat(down.calls()).isGreaterThan(calls);
  }

  @Test
  void aPermanentFailureDoesNotOpenTheWindow() {
    var refusing = new InMemoryRegistry().failingWith(status(404));
    var wire404 = new AvroWire(refusing, Map.of(WireFormat.REGISTRY_BACKOFF_MS, "60000"));
    var bytes = wire.encode(EventSamples.closed(), new RecordHeaders());

    wire404.decode(TOPIC, new RecordHeaders(), bytes);
    var calls = refusing.calls();
    wire404.decode(TOPIC, new RecordHeaders(), bytes);

    assertThat(refusing.calls()).isGreaterThan(calls);
  }

  @Test
  void theBackoffWindowDefaultsToFiveSeconds() {
    assertThat(WireFormat.registryBackoff(Map.of())).isEqualTo(java.time.Duration.ofSeconds(5));
    assertThat(WireFormat.registryBackoff(Map.of(WireFormat.REGISTRY_BACKOFF_MS, "250")))
        .isEqualTo(java.time.Duration.ofMillis(250));
  }
}
