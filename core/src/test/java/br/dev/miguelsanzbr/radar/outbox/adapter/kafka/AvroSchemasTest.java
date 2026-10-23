package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.JsonProperties;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Avro schemas mirror the JSON Schemas of the contracts module: same field names at every level
 * and the same set of fields that can never be null. A field that admits null is optional in Avro
 * (a union with null and a default), so "required" here means required and not nullable.
 */
class AvroSchemasTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SNAPSHOT = "procurement-snapshot.v1.schema.json";

  /** The fields of an object, each with whether it is required and its own fields, if any. */
  private record Shape(Map<String, Field> fields) {}

  private record Field(boolean required, Shape nested) {}

  private static JsonNode jsonSchema(String file) {
    try (var in = AvroSchemasTest.class.getClassLoader().getResourceAsStream("events/" + file)) {
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean admitsNull(JsonNode schema) {
    var type = schema.path("type");
    if (type.isArray()) {
      return contains(type, "null");
    }
    var anyOf = schema.path("anyOf");
    var enumeration = schema.path("enum");
    return anyOf.isArray() && anyOf.toString().contains("\"type\":\"null\"")
        || enumeration.isArray() && enumeration.toString().contains("null");
  }

  private static boolean contains(JsonNode array, String text) {
    for (var item : array) {
      if (item.asString().equals(text)) {
        return true;
      }
    }
    return false;
  }

  /** A schema node and the document its local references point into. */
  private record Resolved(JsonNode node, JsonNode owner) {}

  /** Follows a $ref (only the local and snapshot ones the contracts use) and unwraps null-or. */
  private static Resolved resolve(JsonNode schema, JsonNode owner) {
    var ref = schema.path("$ref").asString("");
    if (ref.isEmpty()) {
      var anyOf = schema.path("anyOf");
      return anyOf.isArray() ? resolve(anyOf.get(1), owner) : new Resolved(schema, owner);
    }
    var local = ref.startsWith("#") ? owner : jsonSchema(SNAPSHOT);
    var pointer = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : "";
    return resolve(pointer.isEmpty() ? local : local.at(pointer), local);
  }

  private static Shape jsonShape(JsonNode properties, JsonNode required, JsonNode owner) {
    var fields = new TreeMap<String, Field>();
    for (var name : properties.propertyNames()) {
      var property = properties.get(name);
      var resolved = resolve(property, owner);
      var node = resolved.node();
      var isRequired = contains(required, name) && !admitsNull(property) && !admitsNull(node);
      fields.put(
          name,
          new Field(
              isRequired,
              node.has("properties")
                  ? jsonShape(node.get("properties"), node.path("required"), resolved.owner())
                  : null));
    }
    return new Shape(fields);
  }

  /** The envelope fields with the payload of the event type. */
  private static Shape jsonEvent(String file) {
    var envelope = jsonSchema("envelope.schema.json");
    var event = jsonSchema(file);
    var properties = (tools.jackson.databind.node.ObjectNode) envelope.get("properties").deepCopy();
    properties.set("payload", event.at("/properties/payload"));
    return jsonShape(properties, envelope.get("required"), event);
  }

  private static Shape avroShape(Schema record) {
    var fields = new TreeMap<String, Field>();
    for (var field : record.getFields()) {
      var type = field.schema();
      var nullable = type.getType() == Schema.Type.UNION;
      var inner = nullable ? type.getTypes().get(1) : type;
      fields.put(
          field.name(),
          new Field(!nullable, inner.getType() == Schema.Type.RECORD ? avroShape(inner) : null));
    }
    return new Shape(fields);
  }

  @ParameterizedTest
  @CsvSource({
    "procurement-discovered,ProcurementDiscovered",
    "procurement-updated,ProcurementUpdated",
    "procurement-situation-changed,ProcurementSituationChanged",
    "procurement-closed,ProcurementClosed",
    "alert-matched,AlertMatched"
  })
  void everyEventDeclaresTheFieldsAndTheRequiredSetOfItsJsonSchema(String file, String type) {
    var schema = AvroSchemas.forEventType(type);

    assertThat(schema.getFullName()).isEqualTo("br.dev.miguelsanzbr.radar.events." + type);
    assertThat(avroShape(schema)).isEqualTo(jsonEvent(file + ".v1.schema.json"));
  }

  @Test
  void theEnvelopeDeclaresTheFieldsAndTheRequiredSetOfItsJsonSchema() {
    var envelope = jsonSchema("envelope.schema.json");
    var json = jsonShape(envelope.get("properties"), envelope.get("required"), envelope);

    assertThat(avroShape(AvroSchemas.envelope())).isEqualTo(json);
  }

  @Test
  void everyOptionalFieldIsAUnionOfNullAndATypeWithANullDefault() {
    var counted = new AtomicInteger();
    assertOptionalsAreNullUnions(AvroSchemas.envelope(), counted);
    for (var schema : AvroSchemas.all()) {
      assertOptionalsAreNullUnions(schema, counted);
    }

    assertThat(counted).hasValueGreaterThan(20);
  }

  private static void assertOptionalsAreNullUnions(Schema record, AtomicInteger counted) {
    for (var field : record.getFields()) {
      var type = field.schema();
      if (type.getType() == Schema.Type.UNION) {
        counted.incrementAndGet();
        assertThat(type.getTypes()).hasSize(2);
        assertThat(type.getTypes().get(0).getType()).isEqualTo(Schema.Type.NULL);
        assertThat(field.defaultVal()).as(field.name()).isEqualTo(JsonProperties.NULL_VALUE);
        type = type.getTypes().get(1);
      }
      if (type.getType() == Schema.Type.RECORD) {
        assertOptionalsAreNullUnions(type, counted);
      }
    }
  }

  @Test
  void anEventTypeWithoutASchemaIsRejected() {
    assertThatThrownBy(() -> AvroSchemas.forEventType("Nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nope");
  }

  @Test
  void anUnreadableSchemaFileIsAnUncheckedIoError() {
    var broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("disk gone");
          }
        };

    assertThatThrownBy(() -> AvroSchemas.parse(broken))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("disk gone");
  }

  @Test
  void aMissingSchemaFileNamesTheResource() {
    assertThatThrownBy(() -> AvroSchemas.load("avro/nope.avsc"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("avro/nope.avsc");
  }
}
