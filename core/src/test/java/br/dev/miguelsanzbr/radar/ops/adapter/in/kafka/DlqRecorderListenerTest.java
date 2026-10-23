package br.dev.miguelsanzbr.radar.ops.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.domain.DeadMessage;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.AvroWire;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.EventSamples;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class DlqRecorderListenerTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

  private final List<DeadMessage> recorded = new ArrayList<>();
  private final DlqMessages messages =
      new DlqMessages() {
        @Override
        public void record(DeadMessage message) {
          recorded.add(message);
        }

        @Override
        public List<Pending> pending(String topic, Long id, int limit) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void markReprocessed(long id, Instant at) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(long id, String result) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<DlqCount> unreprocessed() {
          throw new UnsupportedOperationException();
        }
      };

  private final DlqRecorderListener listener =
      new DlqRecorderListener(messages, Clock.fixed(NOW, ZoneOffset.UTC));

  private static void header(ConsumerRecord<?, ?> record, String name, String value) {
    record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void theRecordIsStoredWithItsCoordinatesPayloadHeadersCauseAndOrigin() {
    var record = new ConsumerRecord<>("radar.procurement.v1.dlq", 4, 99L, "key", "value");
    header(record, DeadLetters.ERROR_CLASS, "java.lang.IllegalStateException");
    header(record, DeadLetters.ERROR_MESSAGE, "odd");
    header(record, DeadLetters.ORIGINAL_TOPIC, "radar.procurement.v1");
    header(record, DeadLetters.ORIGINAL_PARTITION, "7");
    header(record, DeadLetters.ORIGINAL_OFFSET, "17");
    header(record, DeadLetters.CONSUMER_GROUP, "search-projector");

    listener.onMessage(record);

    assertThat(recorded)
        .containsExactly(
            new DeadMessage(
                "radar.procurement.v1.dlq",
                4,
                99L,
                "key",
                "value",
                Map.of(
                    DeadLetters.ERROR_CLASS,
                    "java.lang.IllegalStateException",
                    DeadLetters.ERROR_MESSAGE,
                    "odd",
                    DeadLetters.ORIGINAL_TOPIC,
                    "radar.procurement.v1",
                    DeadLetters.ORIGINAL_PARTITION,
                    "7",
                    DeadLetters.ORIGINAL_OFFSET,
                    "17",
                    DeadLetters.CONSUMER_GROUP,
                    "search-projector"),
                "java.lang.IllegalStateException",
                "odd",
                "radar.procurement.v1",
                7,
                17L,
                "search-projector",
                NOW));
  }

  @Test
  void aRecordWithoutOriginHeadersIsKeyedOnItsOwnCoordinatesWithNoGroup() {
    listener.onMessage(new ConsumerRecord<String, String>("radar.alert.v1.dlq", 0, 1L, null, null));

    assertThat(recorded)
        .containsExactly(
            new DeadMessage(
                "radar.alert.v1.dlq",
                0,
                1L,
                null,
                null,
                Map.of(),
                null,
                null,
                "radar.alert.v1.dlq",
                0,
                1L,
                "",
                NOW));
  }

  @Test
  void anOriginThatIsNotANumberFallsBackToTheOwnCoordinates() {
    var record = new ConsumerRecord<>("radar.alert.v1.dlq", 3, 5L, "k", "v");
    header(record, DeadLetters.ORIGINAL_TOPIC, "radar.alert.v1");
    header(record, DeadLetters.ORIGINAL_PARTITION, "x");
    header(record, DeadLetters.ORIGINAL_OFFSET, "y");

    listener.onMessage(record);

    var message = recorded.getFirst();
    assertThat(message.originalTopic()).isEqualTo("radar.alert.v1.dlq");
    assertThat(message.originalPartition()).isEqualTo(3);
    assertThat(message.originalOffset()).isEqualTo(5L);
  }

  @Test
  void anIncompleteOriginFallsBackToTheOwnCoordinates() {
    var onlyTopic = new ConsumerRecord<>("radar.alert.v1.dlq", 3, 5L, "k", "v");
    header(onlyTopic, DeadLetters.ORIGINAL_TOPIC, "radar.alert.v1");
    var noOffset = new ConsumerRecord<>("radar.alert.v1.dlq", 3, 6L, "k", "v");
    header(noOffset, DeadLetters.ORIGINAL_TOPIC, "radar.alert.v1");
    header(noOffset, DeadLetters.ORIGINAL_PARTITION, "1");

    listener.onMessage(onlyTopic);
    listener.onMessage(noOffset);

    assertThat(recorded)
        .extracting(DeadMessage::originalTopic, DeadMessage::originalOffset)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("radar.alert.v1.dlq", 5L),
            org.assertj.core.groups.Tuple.tuple("radar.alert.v1.dlq", 6L));
  }

  @Test
  void aHeaderWithoutValueIsLeftOut() {
    var record = new ConsumerRecord<>("radar.alert.v1.dlq", 0, 1L, "key", "value");
    record.headers().add("flag", null);

    listener.onMessage(record);

    assertThat(recorded.getFirst().headers()).isEmpty();
  }

  @Test
  void anAvroRecordIsStoredAsTheJsonTextTheDeserializerGaveIt() {
    var json = EventSamples.closed();
    var record =
        new ConsumerRecord<>(
            "radar.procurement.v1.dlq", 0, 3L, "key", new AvroWire().roundTrip(json));

    listener.onMessage(record);

    assertThat(recorded.getFirst().value()).isEqualTo(record.value());
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    assertThat(mapper.readTree(record.value())).isEqualTo(mapper.readTree(json));
  }

  @Test
  void anAvroRecordThatCouldNotBeDecodedIsStoredWithItsBytesInBase64() {
    listener.onMessage(
        new ConsumerRecord<>("radar.alert.v1.dlq", 0, 3L, "key", "avro-undecodable:AAAAAQ=="));

    assertThat(recorded.getFirst().value()).isEqualTo("avro-undecodable:AAAAAQ==");
  }

  @Test
  void aRecordLeftMarkedForARegistryRetryIsRejectedAsAnOutageAndNothingIsStored() {
    var record =
        new ConsumerRecord<>(
            "radar.alert.v1.dlq",
            0,
            3L,
            "key",
            br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException.MARKER + "AAAA");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onMessage(record))
        .isInstanceOf(br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException.class);
    assertThat(recorded).isEmpty();
  }
}
