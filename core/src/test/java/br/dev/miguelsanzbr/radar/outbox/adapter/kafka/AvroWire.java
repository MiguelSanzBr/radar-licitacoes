package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Avro wire format end to end without a broker or a registry server: the serializer and the
 * deserializer of the app, configured for avro mode, over one {@link InMemoryRegistry}.
 */
public final class AvroWire {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final InMemoryRegistry registry;
  private final EnvelopeSerializer serializer;
  private final EnvelopeDeserializer deserializer;

  public AvroWire() {
    this(new InMemoryRegistry());
  }

  public AvroWire(InMemoryRegistry registry) {
    this(registry, Map.of());
  }

  /** With more client settings, for example {@code radar.messaging.registry-timeout-ms}. */
  public AvroWire(InMemoryRegistry registry, Map<String, Object> more) {
    this.registry = registry;
    var config = new HashMap<String, Object>(more);
    config.put(WireFormat.FORMAT, "avro");
    config.put("apicurio.registry.url", "http://registry.invalid");
    serializer = new EnvelopeSerializer(registry);
    serializer.configure(config, false);
    deserializer = new EnvelopeDeserializer(registry);
    deserializer.configure(config, false);
  }

  public InMemoryRegistry registry() {
    return registry;
  }

  /** The topic the outbox publishes the aggregate type of the envelope to. */
  public static String topicOf(String envelope) {
    return "Alert".equals(JSON.readTree(envelope).get("aggregateType").asString())
        ? "radar.alert.v1"
        : "radar.procurement.v1";
  }

  /** The bytes of the record the producer would send, headers filled in. */
  public byte[] encode(String envelope, Headers headers) {
    return serializer.serialize(topicOf(envelope), headers, envelope);
  }

  public String decode(String topic, Headers headers, byte[] value) {
    return deserializer.deserialize(topic, headers, value);
  }

  /** The envelope as a consumer of the avro record reads it: JSON text again. */
  public String roundTrip(String envelope) {
    var headers = new RecordHeaders();
    return decode(topicOf(envelope), headers, encode(envelope, headers));
  }
}
