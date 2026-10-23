package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One envelope of each event type, as the outbox relay writes them (the JSON contract). */
public final class EventSamples {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static final String PROCUREMENT_ID = "83021808000182-1-000001/2026";
  public static final String SUBSCRIBER_ID = "0198a3c2-4f10-7000-8000-0000000000a1";
  public static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private EventSamples() {}

  /** Event type to its envelope, in the order of the events. */
  public static Map<String, String> all() {
    var samples = new LinkedHashMap<String, String>();
    samples.put("ProcurementDiscovered", discovered());
    samples.put("ProcurementUpdated", updated());
    samples.put("ProcurementSituationChanged", situationChanged());
    samples.put("ProcurementClosed", closed());
    samples.put("AlertMatched", alertMatched());
    return samples;
  }

  public static ObjectNode snapshot() {
    return (ObjectNode)
        JSON.readTree(
            """
            {"id":"83021808000182-1-000001/2026","status":"PUBLISHED",
             "situation":{"id":1,"name":"Divulgada"},
             "organ":{"cnpj":"83021808000182","name":"Prefeitura de Exemplo"},
             "unit":{"code":"1010","name":"Secretaria de Compras","uf":"SC",
                     "municipalityName":"Exemplo","ibgeCode":"4205407"},
             "modality":{"id":6,"name":"Pregao Eletronico"},
             "disputeMode":{"id":1,"name":"Aberto"},
             "legalBasis":{"code":1,"name":"Lei 14.133/2021, Art. 28, I"},"srp":false,
             "estimatedValue":"1500.50","awardedValue":null,
             "proposalWindow":{"openingAt":"2026-10-23T12:00:00Z","closingAt":"2026-10-26T12:00:00Z"},
             "publishedAt":"2026-10-21T12:00:00Z","pncpUpdatedAt":"2026-10-22T10:00:00Z",
             "objectDescription":"Aquisição de notebooks para a secretaria de educação",
             "revision":1}
            """);
  }

  private static ObjectNode envelope(
      String type, String aggregateType, String aggregateId, ObjectNode payload) {
    var node = JSON.createObjectNode();
    node.put("eventId", "0198a3c2-4f10-7000-8000-000000000001");
    node.put("eventType", type);
    node.put("schemaVersion", 1);
    node.put("aggregateType", aggregateType);
    node.put("aggregateId", aggregateId);
    node.put("aggregateVersion", 3);
    node.put("occurredAt", "2026-10-22T12:00:00Z");
    node.put("traceparent", TRACEPARENT);
    node.set("payload", payload);
    return node;
  }

  private static ObjectNode procurementPayload() {
    var payload = JSON.createObjectNode();
    payload.put("aggregateId", PROCUREMENT_ID);
    payload.put("occurredAt", "2026-10-22T12:00:00Z");
    return payload;
  }

  private static ObjectNode procurement(String type, ObjectNode payload) {
    return envelope(type, "Procurement", PROCUREMENT_ID, payload);
  }

  public static String discovered() {
    var payload = procurementPayload();
    payload.set("snapshot", snapshot());
    return procurement("ProcurementDiscovered", payload).toString();
  }

  public static String updated() {
    var payload = procurementPayload();
    payload.set("snapshot", snapshot());
    payload.putArray("changedFields").add("estimatedValue").add("objectDescription");
    return procurement("ProcurementUpdated", payload).toString();
  }

  public static String situationChanged() {
    var payload = procurementPayload();
    payload.putObject("from").put("id", 1).put("name", "Divulgada");
    payload.putObject("to").put("id", 2).put("name", "Revogada");
    payload.put("pncpUpdatedAt", "2026-10-22T11:00:00Z");
    payload.put("unexpected", true);
    return procurement("ProcurementSituationChanged", payload).toString();
  }

  public static String closed() {
    var payload = procurementPayload();
    payload.put("closingAt", "2026-10-26T12:00:00Z");
    return procurement("ProcurementClosed", payload).toString();
  }

  public static String alertMatched() {
    var payload = JSON.createObjectNode();
    payload.put("savedSearchId", "0198a3c2-4f10-7000-8000-0000000000b1");
    payload.put("subscriberId", SUBSCRIBER_ID);
    payload.put("procurementId", PROCUREMENT_ID);
    payload.put("frequency", "INSTANT");
    payload.put("objectDescription", "Aquisição de notebooks");
    payload.put("organName", "Prefeitura de Exemplo");
    payload.putNull("uf");
    payload.put("municipality", "Exemplo");
    payload.put("estimatedValue", "1500.50");
    payload.putNull("proposalClosingAt");
    payload.put("detailPath", "/licitacoes/83021808000182-1-000001-2026");
    return envelope("AlertMatched", "Alert", SUBSCRIBER_ID, payload).toString();
  }
}
