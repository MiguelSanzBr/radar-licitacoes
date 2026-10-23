package br.dev.miguelsanzbr.radar.search.domain;

import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.util.Set;

/**
 * Optional narrowing of a search; every null means "any". {@code open} only restricts when true.
 */
public record Filters(
    Uf uf,
    IbgeCode ibge,
    Integer modalityId,
    Money minValue,
    Money maxValue,
    Boolean open,
    String status) {

  private static final Set<String> STATUSES =
      Set.of("PUBLISHED", "SUSPENDED", "REVOKED", "ANNULLED");

  public static final Filters NONE = new Filters(null, null, null, null, null, null, null);

  public Filters {
    if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
      throw new IllegalArgumentException("minValue must not exceed maxValue");
    }
    if (status != null && !STATUSES.contains(status)) {
      throw new IllegalArgumentException("invalid status [" + status + "]");
    }
  }

  public boolean openOnly() {
    return Boolean.TRUE.equals(open);
  }
}
