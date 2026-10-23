package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import br.dev.miguelsanzbr.radar.shared.RegistryUnavailableException;
import io.apicurio.registry.resolver.client.RegistryClientFacade;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The value deserializer of every consumer: bytes in, the JSON text of the envelope out, whichever
 * format the producer wrote, so a consumer accepts both during the transition. The format is the
 * first byte: zero is the Confluent magic byte and a JSON text never starts with it. A {@code
 * content-type} header that names another type turns an Avro-looking value back into text.
 *
 * <p>An Avro record that cannot be decoded never throws into the poll loop; it becomes a text that
 * no JSON parser accepts, with the bytes in Base64. When the cause is permanent (a schema id the
 * registry does not know, bad bytes) the text starts with {@code avro-undecodable:}, the listener
 * rejects it as malformed and the record goes to the dead-letter topic with its bytes intact. When
 * the registry could not be reached, did not answer in time or answered with a server error, the
 * text starts with {@link RegistryUnavailableException#MARKER}: the listeners reject it as an
 * outage and the container retries the record, which is decoded again on each attempt. After such a
 * failure the deserializer marks every Avro record at once for {@code
 * radar.messaging.registry-backoff-ms} (five seconds by default) without calling the registry, so a
 * poll of hundreds of records does not wait the timeout for each of them; a record whose schema is
 * cached waits that window too.
 */
public final class EnvelopeDeserializer implements Deserializer<String> {

  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeDeserializer.class);

  private final RegistryClientFacade registry;
  private Map<String, ?> configs = Map.of();
  private boolean isKey;
  private Duration registryTimeout = WireFormat.registryTimeout(Map.of());
  private Duration registryBackoff = WireFormat.registryBackoff(Map.of());
  // System.nanoTime() before which Avro records are marked without calling the registry
  private long registryRetryAt = System.nanoTime();
  private AvroKafkaDeserializer<GenericRecord> avro;

  /** Used by Kafka, which instantiates the class named in {@code value.deserializer}. */
  public EnvelopeDeserializer() {
    this(null);
  }

  /** With a registry client of the caller (tests); {@code null} builds it from the config. */
  EnvelopeDeserializer(RegistryClientFacade registry) {
    this.registry = registry;
  }

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {
    this.configs = Map.copyOf(configs);
    this.registryTimeout = WireFormat.registryTimeout(configs);
    this.registryBackoff = WireFormat.registryBackoff(configs);
    this.isKey = isKey;
  }

  @Override
  public String deserialize(String topic, byte[] data) {
    return deserialize(topic, new RecordHeaders(), data);
  }

  @Override
  public String deserialize(String topic, Headers headers, byte[] data) {
    if (data == null) {
      return null;
    }
    if (!isAvro(headers, data)) {
      return new String(data, StandardCharsets.UTF_8);
    }
    if (System.nanoTime() - registryRetryAt < 0) {
      // why: a poll holds up to 500 records and each failing call would wait the whole timeout
      return RegistryUnavailableException.MARKER + Base64.getEncoder().encodeToString(data);
    }
    try {
      return WireFormat.within(
          registryTimeout,
          () -> GenericData.get().toString(avro().deserialize(topic, headers, data)));
    } catch (RuntimeException e) {
      var retry = WireFormat.transientFailure(e);
      if (retry) {
        registryRetryAt = System.nanoTime() + registryBackoff.toNanos();
      }
      LOG.warn(
          "avro record of {} could not be decoded (retry: {}): {}", topic, retry, e.toString());
      var marker = retry ? RegistryUnavailableException.MARKER : WireFormat.UNDECODABLE;
      return marker + Base64.getEncoder().encodeToString(data);
    }
  }

  private static boolean isAvro(Headers headers, byte[] data) {
    // an Avro record always starts with the magic byte; a header alone is not enough, because a
    // dead-letter record can inherit the content type of the Avro record it came from
    if (data.length == 0 || data[0] != WireFormat.MAGIC) {
      return false;
    }
    var type = headers.lastHeader(WireFormat.CONTENT_TYPE);
    return type == null || WireFormat.AVRO.equals(new String(type.value(), StandardCharsets.UTF_8));
  }

  /** Built on the first Avro record, so a JSON-only deployment never needs the registry. */
  private AvroKafkaDeserializer<GenericRecord> avro() {
    if (avro == null) {
      var deserializer =
          registry == null
              ? new AvroKafkaDeserializer<GenericRecord>()
              : new AvroKafkaDeserializer<GenericRecord>(registry);
      deserializer.configure(WireFormat.registryConfig(configs), isKey);
      avro = deserializer;
    }
    return avro;
  }

  @Override
  public void close() {
    if (avro != null) {
      avro.close();
    }
  }
}
