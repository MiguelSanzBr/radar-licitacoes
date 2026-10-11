package br.dev.miguelsanzbr.radar.shared;

import java.util.regex.Pattern;

/** Seven-digit IBGE municipality code; the first digit is the region (1 to 5). */
public record IbgeCode(String value) {

  private static final Pattern FORMAT = Pattern.compile("^[1-5]\\d{6}$");

  public IbgeCode {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid ibge code [" + value + "]");
    }
  }
}
