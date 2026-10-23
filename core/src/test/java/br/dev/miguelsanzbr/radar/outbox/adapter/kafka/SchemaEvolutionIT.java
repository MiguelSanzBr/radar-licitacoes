package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.SharedApicurio;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The compatibility rule of the registry, against a real Apicurio. The schema is the real
 * ProcurementClosed; each test works in a group of its own, with its own rule. Every response is
 * printed with the {@code EVOLUTION} prefix: docs/benchmarks/schema/evolution.md quotes them.
 */
@Tag("avro")
class SchemaEvolutionIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ARTIFACT = "ProcurementClosed";

  private final RegistryHttp registry = new RegistryHttp(SharedApicurio.url());
  private String group;

  private static String closed() {
    return AvroSchemas.forEventType(ARTIFACT).toString();
  }

  private static String payloadWith(String schema, java.util.function.Consumer<ArrayNode> edit) {
    var tree = (ObjectNode) JSON.readTree(schema);
    var payload = (ObjectNode) tree.get("fields").get(8).get("type");
    edit.accept((ArrayNode) payload.get("fields"));
    return tree.toString();
  }

  private static void remove(ArrayNode fields, String name) {
    for (var i = 0; i < fields.size(); i++) {
      if (fields.get(i).get("name").asString().equals(name)) {
        fields.remove(i);
        return;
      }
    }
  }

  private static ObjectNode field(String name, Object type) {
    var field = JSON.createObjectNode().put("name", name);
    field.set("type", JSON.valueToTree(type));
    return field;
  }

  private String ruleOf(String rule) {
    return "{\"ruleType\":\"COMPATIBILITY\",\"config\":\"" + rule + "\"}";
  }

  private void group(String rule) {
    group = "radar.evolution." + UUID.randomUUID();
    assertThat(registry.call("POST", "/groups", "{\"groupId\":\"" + group + "\"}").status())
        .isEqualTo(200);
    assertThat(registry.call("POST", "/groups/" + group + "/rules", ruleOf(rule)).status())
        .isEqualTo(204);
  }

  private static String content(String schema) {
    return JSON.createObjectNode()
        .put("content", schema)
        .put("contentType", "application/json")
        .toString();
  }

  private RegistryHttp.Response register(String version, String schema) {
    var path = "/groups/" + group + "/artifacts";
    if (version.equals("1")) {
      var body = JSON.createObjectNode().put("artifactId", ARTIFACT).put("artifactType", "AVRO");
      body.putObject("firstVersion")
          .put("version", version)
          .set("content", JSON.readTree(content(schema)));
      return log("v1 registers", registry.call("POST", path, body.toString()));
    }
    var body = JSON.createObjectNode().put("version", version);
    body.set("content", JSON.readTree(content(schema)));
    return registry.call("POST", path + "/" + ARTIFACT + "/versions", body.toString());
  }

  private static RegistryHttp.Response log(String step, RegistryHttp.Response response) {
    System.out.println("EVOLUTION " + step + " | " + response.status() + " | " + response.body());
    return response;
  }

  @BeforeEach
  void aBackwardGroupWithTheFirstVersion() {
    group("BACKWARD");
    assertThat(register("1", closed()).status()).isEqualTo(200);
  }

  @Test
  void anOptionalFieldWithADefaultRegistersAsTheNextVersion() {
    var v2 =
        payloadWith(
            closed(),
            fields ->
                fields.add(field("note", java.util.List.of("null", "string")).putNull("default")));

    var response = log("v2 optional field with default", register("2", v2));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).contains("\"version\":\"2\"");
  }

  @Test
  void aRequiredFieldWithoutADefaultIsRejectedByTheRegistry() {
    var v2 = payloadWith(closed(), fields -> fields.add(field("agency", "string")));

    var response = log("v2 required field without default", register("2", v2));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).contains("RuleViolationException").contains("agency");
  }

  @Test
  void removingARequiredFieldPassesBackwardBecauseTheNewReaderIgnoresWhatItDoesNotKnow() {
    var v2 = payloadWith(closed(), fields -> remove(fields, "closingAt"));

    var response = log("v2 removes required field, BACKWARD", register("2", v2));

    assertThat(response.status()).isEqualTo(200);
  }

  @Test
  void removingARequiredFieldIsRejectedWhenTheGroupIsFull() {
    group("FULL");
    register("1", closed());
    var v2 = payloadWith(closed(), fields -> remove(fields, "closingAt"));

    var response = log("v2 removes required field, FULL", register("2", v2));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).contains("RuleViolationException").contains("closingAt");
  }

  @Test
  void changingTheTypeOfAFieldIsRejected() {
    var v2 =
        payloadWith(
            closed(),
            fields -> {
              for (var f : fields) {
                if (f.get("name").asString().equals("closingAt")) {
                  ((ObjectNode) f).put("type", "long");
                }
              }
            });

    var response = log("v2 changes closingAt from string to long", register("2", v2));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).contains("RuleViolationException");
  }

  @Test
  void theSerializerCountsARuleViolationOfTheRegistryAsASerializationError() {
    // the group already holds a ProcurementClosed whose closingAt is a long: the schema of the code
    // (a string) is incompatible with it
    var old =
        payloadWith(
            closed(),
            fields -> {
              remove(fields, "closingAt");
              fields.add(field("closingAt", "long"));
            });
    group("BACKWARD");
    assertThat(register("1", old).status()).isEqualTo(200);
    var serializer = new EnvelopeSerializer();
    serializer.configure(
        Map.of(WireFormat.FORMAT, "avro", "apicurio.registry.url", SharedApicurio.url()), false);
    var topic = group;

    assertThatThrownBy(
            () -> serializer.serialize(topic, new RecordHeaders(), EventSamples.closed()))
        .isInstanceOf(SerializationException.class)
        .isNotInstanceOf(RetriableException.class);
    serializer.close();
  }
}
