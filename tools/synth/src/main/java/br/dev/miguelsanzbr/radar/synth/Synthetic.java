package br.dev.miguelsanzbr.radar.synth;

import br.dev.miguelsanzbr.radar.synth.Corpus.IdName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One generated procurement, already in the shape of the read model and of the event snapshot. */
record Synthetic(
    String id,
    String objectDescription,
    String organCnpj,
    String organName,
    String unitCode,
    String unitName,
    String uf,
    String municipality,
    String ibgeCode,
    IdName modality,
    IdName situation,
    String status,
    IdName disputeMode,
    IdName legalBasis,
    boolean srp,
    BigDecimal estimatedValue,
    Instant openingAt,
    Instant closingAt,
    Instant publishedAt,
    Instant pncpUpdatedAt) {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Map<Integer, String> STATUS =
      Map.of(1, "PUBLISHED", 2, "REVOKED", 3, "ANNULLED", 4, "SUSPENDED");
  private static final BigDecimal FIFTY_THOUSAND = BigDecimal.valueOf(50_000);
  private static final BigDecimal FIVE_HUNDRED_THOUSAND = BigDecimal.valueOf(500_000);
  private static final BigDecimal FIVE_MILLION = BigDecimal.valueOf(5_000_000);

  /** The status the read side gives a PNCP situation. */
  static String statusOf(int situationId) {
    return STATUS.getOrDefault(situationId, "PUBLISHED");
  }

  /** 0: no value, 1: under 50k, 2: under 500k, 3: under 5mi, 4: 5mi or more. */
  static short bandOf(BigDecimal value) {
    if (value == null) {
      return 0;
    }
    if (value.compareTo(FIFTY_THOUSAND) < 0) {
      return 1;
    }
    if (value.compareTo(FIVE_HUNDRED_THOUSAND) < 0) {
      return 2;
    }
    return (short) (value.compareTo(FIVE_MILLION) < 0 ? 3 : 4);
  }

  short valueBand() {
    return bandOf(estimatedValue);
  }

  /** The procurement snapshot of the event contract (procurement-snapshot.v1). */
  ObjectNode snapshot() {
    var node = JSON.createObjectNode();
    node.put("id", id);
    node.put("status", status);
    node.putObject("situation").put("id", situation.id()).put("name", situation.name());
    node.putObject("organ").put("cnpj", organCnpj).put("name", organName);
    node.putObject("unit")
        .put("code", unitCode)
        .put("name", unitName)
        .put("uf", uf)
        .put("municipalityName", municipality)
        .put("ibgeCode", ibgeCode);
    node.putObject("modality").put("id", modality.id()).put("name", modality.name());
    named(node, "disputeMode", "id", disputeMode);
    named(node, "legalBasis", "code", legalBasis);
    node.put("srp", srp);
    node.put("estimatedValue", estimatedValue == null ? null : estimatedValue.toPlainString());
    node.putNull("awardedValue");
    if (openingAt == null) {
      node.putNull("proposalWindow");
    } else {
      node.putObject("proposalWindow")
          .put("openingAt", openingAt.toString())
          .put("closingAt", closingAt.toString());
    }
    node.put("publishedAt", publishedAt.toString());
    node.put("pncpUpdatedAt", pncpUpdatedAt.toString());
    node.put("objectDescription", objectDescription);
    node.put("revision", 1);
    return node;
  }

  /** The detail view: the snapshot plus the situation history, as the projector stores it. */
  String detail() {
    var node = snapshot();
    var entry = node.putArray("history").addObject();
    entry.putNull("from");
    entry.putObject("to").put("id", situation.id()).put("name", situation.name());
    entry.put("unexpected", false);
    entry.put("pncpUpdatedAt", pncpUpdatedAt.toString());
    entry.put("recordedAt", publishedAt.toString());
    return JSON.writeValueAsString(node);
  }

  private static void named(ObjectNode node, String key, String idKey, IdName value) {
    if (value == null) {
      node.putNull(key);
    } else {
      node.putObject(key).put(idKey, value.id()).put("name", value.name());
    }
  }
}
