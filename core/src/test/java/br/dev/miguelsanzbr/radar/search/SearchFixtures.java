package br.dev.miguelsanzbr.radar.search;

import br.dev.miguelsanzbr.radar.search.application.ProcurementEvent;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.Situation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Events and their envelope JSON, shaped like the contracts module schemas. */
public final class SearchFixtures {

  public static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
  public static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SearchFixtures() {}

  public static String id(int n) {
    return "00000001234567-1-%06d/2026".formatted(n);
  }

  /** A stable event id per seed. */
  public static UUID eventId(String seed) {
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }

  public static Situation situation(int id) {
    return switch (id) {
      case 1 -> new Situation(1, "Divulgada no PNCP");
      case 2 -> new Situation(2, "Revogada");
      case 3 -> new Situation(3, "Anulada");
      case 4 -> new Situation(4, "Suspensa");
      default -> new Situation(id, "Situacao " + id);
    };
  }

  public static ProcurementView view(String id, String object, String estimated, int situationId) {
    return view(id, object, estimated, situationId, 1, T0);
  }

  /** The view and its snapshot JSON are built from the same values. */
  public static ProcurementView view(
      String id,
      String object,
      String estimated,
      int situationId,
      int revision,
      Instant pncpUpdatedAt) {
    var situation = situation(situationId);
    var status =
        switch (situationId) {
          case 1 -> "PUBLISHED";
          case 2 -> "REVOKED";
          case 3 -> "ANNULLED";
          default -> "SUSPENDED";
        };
    var opening = T0.plusSeconds(3600);
    var closing = T0.plusSeconds(86_400);
    var json = JSON.createObjectNode();
    json.put("id", id);
    json.put("status", status);
    json.putObject("situation").put("id", situation.id()).put("name", situation.name());
    json.putObject("organ").put("cnpj", "12345678000190").put("name", "Prefeitura de Teste");
    json.putObject("unit")
        .put("code", "1")
        .put("name", "Secretaria de Saúde")
        .put("uf", "SP")
        .put("municipalityName", "São Paulo")
        .put("ibgeCode", "3550308");
    json.putObject("modality").put("id", 6).put("name", "Pregão Eletrônico");
    json.putNull("disputeMode");
    json.putNull("legalBasis");
    json.put("srp", false);
    putMoney(json, "estimatedValue", estimated);
    json.putNull("awardedValue");
    json.putObject("proposalWindow")
        .put("openingAt", opening.toString())
        .put("closingAt", closing.toString());
    json.put("publishedAt", T0.toString());
    json.put("pncpUpdatedAt", pncpUpdatedAt.toString());
    json.put("objectDescription", object);
    json.put("revision", revision);
    return new ProcurementView(
        id,
        status,
        situation,
        "Prefeitura de Teste",
        "Secretaria de Saúde",
        "SP",
        "São Paulo",
        "3550308",
        6,
        "Pregão Eletrônico",
        estimated == null ? null : new BigDecimal(estimated),
        opening,
        closing,
        T0,
        pncpUpdatedAt,
        object,
        json.toString());
  }

  /** The same procurement filed under another uf, modality and publication instant. */
  public static ProcurementView rebucket(
      ProcurementView v, String uf, int modalityId, Instant publishedAt) {
    return new ProcurementView(
        v.id(),
        v.status(),
        v.situation(),
        v.organName(),
        v.unitName(),
        uf,
        v.municipalityName(),
        v.ibgeCode(),
        modalityId,
        v.modalityName(),
        v.estimatedValue(),
        v.proposalOpeningAt(),
        v.proposalClosingAt(),
        publishedAt,
        v.pncpUpdatedAt(),
        v.objectDescription(),
        v.json());
  }

  public static ProcurementEvent.Discovered discovered(
      String seed, ProcurementView view, long version) {
    return new ProcurementEvent.Discovered(eventId(seed), view.id(), version, T0, view);
  }

  public static ProcurementEvent.Updated updated(String seed, ProcurementView view, long version) {
    return new ProcurementEvent.Updated(
        eventId(seed), view.id(), version, T0.plusSeconds(60), view);
  }

  public static ProcurementEvent.SituationChanged situationChanged(
      String seed, String id, long version, int from, int to) {
    return new ProcurementEvent.SituationChanged(
        eventId(seed),
        id,
        version,
        T0.plusSeconds(60),
        situation(from),
        situation(to),
        T0.plusSeconds(60),
        false);
  }

  public static ProcurementEvent.Closed closed(String seed, String id, long version) {
    return new ProcurementEvent.Closed(
        eventId(seed), id, version, T0.plusSeconds(90_000), T0.plusSeconds(86_400));
  }

  /** The envelope exactly as the outbox publisher writes it. */
  public static String envelope(ProcurementEvent event) {
    var payload = JSON.createObjectNode();
    payload.put("aggregateId", event.aggregateId());
    payload.put("occurredAt", event.occurredAt().toString());
    var type =
        switch (event) {
          case ProcurementEvent.Discovered d -> {
            payload.set("snapshot", JSON.readTree(d.snapshot().json()));
            yield "ProcurementDiscovered";
          }
          case ProcurementEvent.Updated u -> {
            payload.set("snapshot", JSON.readTree(u.snapshot().json()));
            payload.putArray("changedFields").add("objectDescription");
            yield "ProcurementUpdated";
          }
          case ProcurementEvent.SituationChanged s -> {
            payload.putObject("from").put("id", s.from().id()).put("name", s.from().name());
            payload.putObject("to").put("id", s.to().id()).put("name", s.to().name());
            payload.put("pncpUpdatedAt", s.pncpUpdatedAt().toString());
            payload.put("unexpected", s.unexpected());
            yield "ProcurementSituationChanged";
          }
          case ProcurementEvent.Closed c -> {
            payload.put("closingAt", c.closingAt().toString());
            yield "ProcurementClosed";
          }
        };
    return envelope(type, event, payload);
  }

  public static String envelope(String type, ProcurementEvent event, ObjectNode payload) {
    var envelope = JSON.createObjectNode();
    envelope.put("eventId", event.eventId().toString());
    envelope.put("eventType", type);
    envelope.put("schemaVersion", 1);
    envelope.put("aggregateType", "Procurement");
    envelope.put("aggregateId", event.aggregateId());
    envelope.put("aggregateVersion", event.aggregateVersion());
    envelope.put("occurredAt", event.occurredAt().toString());
    envelope.put("traceparent", TRACEPARENT);
    envelope.set("payload", payload);
    return envelope.toString();
  }

  private static void putMoney(ObjectNode json, String name, String value) {
    if (value == null) {
      json.putNull(name);
    } else {
      json.put(name, value);
    }
  }
}
