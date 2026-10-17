package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Everything the aggregate takes from one PNCP item; {@code rawJson} is opaque and not hashed. */
public record PncpSnapshot(
    ProcurementId id,
    PncpSituation situation,
    Organ organ,
    OrganUnit unit,
    Modality modality,
    DisputeMode disputeMode,
    LegalBasis legalBasis,
    boolean srp,
    Money estimatedValue,
    Money awardedValue,
    ProposalWindow proposalWindow,
    Instant publishedAt,
    Instant pncpUpdatedAt,
    String objectDescription,
    String rawJson) {

  public PncpSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(situation, "situation");
    Objects.requireNonNull(organ, "organ");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(modality, "modality");
    Objects.requireNonNull(publishedAt, "publishedAt");
    Objects.requireNonNull(pncpUpdatedAt, "pncpUpdatedAt");
    Objects.requireNonNull(rawJson, "rawJson");
    if (estimatedValue != null && estimatedValue.isNegative()) {
      throw new IllegalArgumentException("estimatedValue must be >= 0: " + estimatedValue);
    }
    objectDescription = objectDescription == null ? "" : objectDescription;
    // a window with no bounds carries no information: same as no window
    if (proposalWindow != null
        && proposalWindow.openingAt() == null
        && proposalWindow.closingAt() == null) {
      proposalWindow = null;
    }
  }

  PncpSnapshot withPublishedAt(Instant instant) {
    return new PncpSnapshot(
        id,
        situation,
        organ,
        unit,
        modality,
        disputeMode,
        legalBasis,
        srp,
        estimatedValue,
        awardedValue,
        proposalWindow,
        instant,
        pncpUpdatedAt,
        objectDescription,
        rawJson);
  }

  /**
   * Named, comparable fields (sorted by name) used to tell which ones differ between two snapshots.
   */
  Map<String, Object> fields() {
    var out = new TreeMap<String, Object>();
    out.put("situation", situation);
    out.put("organ", organ);
    out.put("unit", unit);
    out.put("modality", modality);
    out.put("disputeMode", disputeMode);
    out.put("legalBasis", legalBasis);
    out.put("srp", srp);
    out.put("estimatedValue", estimatedValue);
    out.put("awardedValue", awardedValue);
    out.put("proposalWindow", proposalWindow);
    out.put("publishedAt", publishedAt);
    out.put("pncpUpdatedAt", pncpUpdatedAt);
    out.put("objectDescription", objectDescription);
    return out;
  }
}
