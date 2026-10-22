package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.Situation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the envelope the outbox publishes (contracts module, {@code events/}) into the events of
 * the read side. Fields this version does not know are ignored, so producers can add them.
 */
final class EnvelopeParser {

  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeParser.class);

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          // why: every field of the contract is required (null allowed), so an absent one is a bad
          // message, not a default: a missing aggregateVersion must not become version 0
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  /** The event and the trace it continues. */
  record Parsed(ProcurementEvent event, String traceparent) {}

  record Envelope(
      UUID eventId,
      String eventType,
      String aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      String traceparent,
      JsonNode payload) {}

  record Organ(String name) {}

  record Unit(String name, String uf, String municipalityName, String ibgeCode) {}

  record Modality(int id, String name) {}

  record Window(Instant openingAt, Instant closingAt) {}

  record Snapshot(
      String id,
      String status,
      Situation situation,
      Organ organ,
      Unit unit,
      Modality modality,
      BigDecimal estimatedValue,
      Window proposalWindow,
      Instant publishedAt,
      Instant pncpUpdatedAt,
      String objectDescription) {}

  record Change(Situation from, Situation to, Instant pncpUpdatedAt, boolean unexpected) {}

  record Closing(Instant closingAt) {}

  /**
   * Empty for an event type this version does not project. Malformed JSON or a missing field
   * throws.
   */
  Optional<Parsed> parse(String json) {
    var envelope = JSON.readValue(json, Envelope.class);
    var id = envelope.eventId();
    var aggregate = envelope.aggregateId();
    var version = envelope.aggregateVersion();
    var at = envelope.occurredAt();
    var payload = envelope.payload();
    ProcurementEvent event =
        switch (envelope.eventType()) {
          case "ProcurementDiscovered" ->
              new ProcurementEvent.Discovered(id, aggregate, version, at, snapshot(payload));
          case "ProcurementUpdated" ->
              new ProcurementEvent.Updated(id, aggregate, version, at, snapshot(payload));
          case "ProcurementSituationChanged" -> {
            var change = JSON.treeToValue(payload, Change.class);
            yield new ProcurementEvent.SituationChanged(
                id,
                aggregate,
                version,
                at,
                change.from(),
                change.to(),
                change.pncpUpdatedAt(),
                change.unexpected());
          }
          case "ProcurementClosed" ->
              new ProcurementEvent.Closed(
                  id, aggregate, version, at, JSON.treeToValue(payload, Closing.class).closingAt());
          default -> {
            LOG.info("ignoring event {} of unknown type {}", id, envelope.eventType());
            yield null;
          }
        };
    return Optional.ofNullable(event).map(e -> new Parsed(e, envelope.traceparent()));
  }

  private static ProcurementView snapshot(JsonNode payload) {
    var node = payload.get("snapshot");
    var s = JSON.treeToValue(node, Snapshot.class);
    var window = s.proposalWindow();
    return new ProcurementView(
        s.id(),
        s.status(),
        s.situation(),
        s.organ().name(),
        s.unit().name(),
        s.unit().uf(),
        s.unit().municipalityName(),
        s.unit().ibgeCode(),
        s.modality().id(),
        s.modality().name(),
        s.estimatedValue(),
        window == null ? null : window.openingAt(),
        window == null ? null : window.closingAt(),
        s.publishedAt(),
        s.pncpUpdatedAt(),
        s.objectDescription(),
        JSON.writeValueAsString(node));
  }
}
