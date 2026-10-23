package br.dev.miguelsanzbr.radar.search.application;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.List;

/**
 * A procurement with its situation history. {@code id} is the control number as stored (with the
 * slash) and {@code etag} the hash of the stored detail, a strong validator.
 */
public record ProcurementDetail(
    String id,
    String objectDescription,
    String organCnpj,
    String organName,
    String unitCode,
    String unitName,
    String uf,
    String municipalityName,
    String ibgeCode,
    int modalityId,
    String modalityName,
    String disputeMode,
    String legalBasis,
    boolean srp,
    String status,
    Money estimatedValue,
    Money awardedValue,
    Instant proposalOpeningAt,
    Instant proposalClosingAt,
    Instant publishedAt,
    Instant pncpUpdatedAt,
    List<SituationChange> history,
    String etag) {

  public ProcurementDetail {
    history = List.copyOf(history);
  }
}
