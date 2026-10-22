package br.dev.miguelsanzbr.radar.alerts.domain;

import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.Uf;

/**
 * Optional criteria of a saved search. Values are validated and kept as normalized text, which is
 * also how the matcher reads them from the stored JSON.
 */
public record AlertFilters(
    String uf, String ibge, Integer modality, String minValue, String maxValue, Boolean open) {

  public static final AlertFilters NONE = new AlertFilters(null, null, null, null, null, null);

  public AlertFilters {
    uf = normalizeUf(uf);
    ibge = normalizeIbge(ibge);
    if (modality != null && modality < 1) {
      throw new InvalidInputException("modality must be positive [" + modality + "]");
    }
    var min = amount(minValue);
    var max = amount(maxValue);
    if (min != null && max != null && min.compareTo(max) > 0) {
      throw new InvalidInputException("minValue is greater than maxValue");
    }
    minValue = min == null ? null : min.amount().toPlainString();
    maxValue = max == null ? null : max.amount().toPlainString();
  }

  private static String normalizeUf(String text) {
    return text == null ? null : InvalidInputException.guard(() -> Uf.of(text).name());
  }

  private static String normalizeIbge(String text) {
    return text == null ? null : InvalidInputException.guard(() -> new IbgeCode(text).value());
  }

  private static Money amount(String text) {
    if (text == null) {
      return null;
    }
    var money = InvalidInputException.guard(() -> Money.of(text));
    if (money.isNegative()) {
      throw new InvalidInputException("amount must not be negative [" + text + "]");
    }
    return money;
  }
}
