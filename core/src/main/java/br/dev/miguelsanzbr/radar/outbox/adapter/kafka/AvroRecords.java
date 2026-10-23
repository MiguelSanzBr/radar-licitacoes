package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.util.ArrayList;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import tools.jackson.databind.JsonNode;

/**
 * Builds the Avro record of an event from its JSON envelope, driven by the schema. A field that is
 * absent takes its default; a field of the wrong type, or absent without a default, is an error
 * that names it. Unions are {@code [null, X]}, the form every schema of the contracts uses.
 */
final class AvroRecords {

  private AvroRecords() {}

  static GenericRecord toRecord(Schema schema, JsonNode json) {
    return (GenericRecord) convert(schema, json, schema.getName());
  }

  private static Object convert(Schema schema, JsonNode json, String name) {
    return switch (schema.getType()) {
      case RECORD -> record(schema, json);
      case UNION -> json.isNull() ? null : convert(schema.getTypes().get(1), json, name);
      case ARRAY -> array(schema, json, name);
      case STRING -> expect(json, json.isString(), "a text", name).stringValue();
      case INT -> expect(json, json.isInt(), "an integer", name).intValue();
      case LONG -> expect(json, json.isIntegralNumber(), "an integer", name).longValue();
      case BOOLEAN -> expect(json, json.isBoolean(), "a boolean", name).booleanValue();
      default -> throw new IllegalArgumentException("unsupported avro type " + schema.getType());
    };
  }

  private static GenericRecord record(Schema schema, JsonNode json) {
    var record = new GenericData.Record(schema);
    for (var field : schema.getFields()) {
      var value = json.get(field.name());
      if (value != null) {
        record.put(field.pos(), convert(field.schema(), value, field.name()));
      } else if (field.hasDefaultValue()) {
        record.put(field.pos(), GenericData.get().getDefaultValue(field));
      } else {
        throw new IllegalArgumentException("missing field " + field.name());
      }
    }
    return record;
  }

  private static Object array(Schema schema, JsonNode json, String name) {
    var items = new ArrayList<>();
    for (var item : expect(json, json.isArray(), "a list", name)) {
      items.add(convert(schema.getElementType(), item, name));
    }
    return items;
  }

  private static JsonNode expect(JsonNode json, boolean ok, String expected, String name) {
    if (!ok) {
      throw new IllegalArgumentException("not " + expected + ": " + name);
    }
    return json;
  }
}
