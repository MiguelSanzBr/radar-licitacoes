package br.dev.miguelsanzbr.radar.shared;

import java.util.Locale;
import java.util.regex.Pattern;

/** Trimmed, lower-cased e-mail address with a pragmatic format check (not full RFC 5322). */
public record Email(String value) {

  private static final int MAX_LENGTH = 254;
  private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

  public Email {
    if (value == null) {
      throw new IllegalArgumentException("invalid email [null]");
    }
    value = value.trim().toLowerCase(Locale.ROOT);
    if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("invalid email [" + value + "]");
    }
  }
}
