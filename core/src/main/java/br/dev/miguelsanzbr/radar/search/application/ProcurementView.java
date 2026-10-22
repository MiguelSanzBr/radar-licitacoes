package br.dev.miguelsanzbr.radar.search.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Normalised snapshot of a procurement as the read side sees it. {@code json} is the snapshot as
 * received, kept whole for the detail view.
 */
public record ProcurementView(
    String id,
    String status,
    Situation situation,
    String organName,
    String unitName,
    String uf,
    String municipalityName,
    String ibgeCode,
    int modalityId,
    String modalityName,
    BigDecimal estimatedValue,
    Instant proposalOpeningAt,
    Instant proposalClosingAt,
    Instant publishedAt,
    Instant pncpUpdatedAt,
    String objectDescription,
    String json) {

  public ProcurementView {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(situation, "situation");
    Objects.requireNonNull(organName, "organName");
    Objects.requireNonNull(modalityName, "modalityName");
    Objects.requireNonNull(publishedAt, "publishedAt");
    Objects.requireNonNull(pncpUpdatedAt, "pncpUpdatedAt");
    Objects.requireNonNull(objectDescription, "objectDescription");
    Objects.requireNonNull(json, "json");
  }

  private static final BigDecimal FIFTY_THOUSAND = BigDecimal.valueOf(50_000);
  private static final BigDecimal FIVE_HUNDRED_THOUSAND = BigDecimal.valueOf(500_000);
  private static final BigDecimal FIVE_MILLION = BigDecimal.valueOf(5_000_000);

  /** 0: no value, 1: under 50k, 2: under 500k, 3: under 5mi, 4: 5mi or more. */
  public short valueBand() {
    if (estimatedValue == null) {
      return 0;
    }
    if (estimatedValue.compareTo(FIFTY_THOUSAND) < 0) {
      return 1;
    }
    if (estimatedValue.compareTo(FIVE_HUNDRED_THOUSAND) < 0) {
      return 2;
    }
    return (short) (estimatedValue.compareTo(FIVE_MILLION) < 0 ? 3 : 4);
  }
}
