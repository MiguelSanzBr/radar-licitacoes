package br.dev.miguelsanzbr.radar.shared;

import java.util.regex.Pattern;

/** PNCP control number, for example {@code 83021808000182-1-000681/2026}. */
public record ProcurementId(String value) {

  private static final Pattern FORMAT = Pattern.compile("^\\d{14}-\\d+-\\d{6}/\\d{4}$");

  public ProcurementId {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid procurement id [" + value + "]");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
