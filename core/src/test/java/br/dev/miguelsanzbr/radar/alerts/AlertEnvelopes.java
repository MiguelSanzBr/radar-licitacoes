package br.dev.miguelsanzbr.radar.alerts;

import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** An AlertMatched envelope as the outbox relay writes it: money as a string, instants in ISO. */
public final class AlertEnvelopes {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AlertEnvelopes() {}

  public static ObjectNode node(
      UUID eventId, AlertMatched a, String traceparent, Instant occurredAt) {
    var node = JSON.createObjectNode();
    node.put("eventId", eventId.toString());
    node.put("eventType", "AlertMatched");
    node.put("schemaVersion", 1);
    node.put("aggregateType", "Alert");
    node.put("aggregateId", a.subscriberId().toString());
    node.put("occurredAt", occurredAt.toString());
    node.put("traceparent", traceparent);
    var payload = node.putObject("payload");
    payload.put("savedSearchId", a.savedSearchId().toString());
    payload.put("subscriberId", a.subscriberId().toString());
    payload.put("procurementId", a.procurementId());
    payload.put("frequency", a.frequency().name());
    payload.put("objectDescription", a.objectDescription());
    payload.put("organName", a.organName());
    payload.put("uf", a.uf());
    payload.put("municipality", a.municipality());
    payload.put(
        "estimatedValue",
        a.estimatedValue() == null ? null : a.estimatedValue().amount().toPlainString());
    payload.put(
        "proposalClosingAt",
        a.proposalClosingAt() == null ? null : a.proposalClosingAt().toString());
    payload.put("detailPath", a.detailPath());
    return node;
  }

  public static String json(UUID eventId, AlertMatched a, Instant occurredAt) {
    return JSON.writeValueAsString(node(eventId, a, null, occurredAt));
  }
}
