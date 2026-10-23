package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import io.apicurio.registry.resolver.client.RegistryClientFacade;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The value serializer of the producer: the JSON text of the envelope in, the bytes of the record
 * out. In {@code json} mode (the default) that is the text as UTF-8. In {@code avro} mode ({@code
 * radar.messaging.format=avro}) it is the Avro record of the event type, registered in the group of
 * the topic, in the Confluent framing; dead-letter topics stay text, because the recorder stores
 * them as text.
 *
 * <p>A message the registry or Avro refuses (a message that cannot be encoded, a rule violation, a
 * 4xx answer) is a {@link SerializationException}: it counts as an attempt of the outbox row and
 * ends as poison like any bad row. A registry that cannot be reached, does not answer within {@code
 * radar.messaging.registry-timeout-ms} or answers 408, 429 or 5xx is a retriable {@link
 * TimeoutException}: the registry is a dependency like the broker, so the rows wait and no attempt
 * is spent.
 */
public final class EnvelopeSerializer implements Serializer<String> {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RegistryClientFacade registry;
  private AvroKafkaSerializer<GenericRecord> avro;
  private Duration registryTimeout;

  /** Used by Kafka, which instantiates the class named in {@code value.serializer}. */
  public EnvelopeSerializer() {
    this(null);
  }

  /** With a registry client of the caller (tests); {@code null} builds it from the config. */
  EnvelopeSerializer(RegistryClientFacade registry) {
    this.registry = registry;
  }

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {
    if ("avro".equalsIgnoreCase(String.valueOf(configs.get(WireFormat.FORMAT)))) {
      registryTimeout = WireFormat.registryTimeout(configs);
      var serializer =
          registry == null
              ? new AvroKafkaSerializer<GenericRecord>()
              : new AvroKafkaSerializer<GenericRecord>(registry);
      serializer.configure(WireFormat.registryConfig(configs), isKey);
      avro = serializer;
    }
  }

  @Override
  public byte[] serialize(String topic, String data) {
    return serialize(topic, new RecordHeaders(), data);
  }

  @Override
  public byte[] serialize(String topic, Headers headers, String data) {
    if (data == null) {
      return null;
    }
    if (avro == null) {
      return data.getBytes(StandardCharsets.UTF_8);
    }
    if (topic.endsWith(KafkaPublisherProperties.DLQ_SUFFIX)) {
      // why: the dead-letter recoverer copies the headers of the record it gives up on, and that
      // record was Avro; this one is text
      headers.remove(WireFormat.CONTENT_TYPE);
      return data.getBytes(StandardCharsets.UTF_8);
    }
    GenericRecord record;
    try {
      var envelope = JSON.readTree(data);
      var type = envelope.path("eventType").asString();
      record = AvroRecords.toRecord(AvroSchemas.forEventType(type), envelope);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new SerializationException("cannot encode the event as avro", e);
    }
    headers.add(WireFormat.CONTENT_TYPE, WireFormat.AVRO.getBytes(StandardCharsets.UTF_8));
    try {
      return WireFormat.within(registryTimeout, () -> avro.serialize(topic, headers, record));
    } catch (RuntimeException e) {
      if (WireFormat.transientFailure(e)) {
        throw new TimeoutException("schema registry unavailable: " + e.getMessage(), e);
      }
      throw new SerializationException(
          "schema registry or avro refused the event: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    if (avro != null) {
      avro.close();
    }
  }
}
