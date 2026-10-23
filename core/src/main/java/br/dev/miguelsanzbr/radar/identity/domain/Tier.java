package br.dev.miguelsanzbr.radar.identity.domain;

import java.util.Locale;

/** Request quota of an API key; the limits per minute come from configuration. */
public enum Tier {
  FREE,
  PRO;

  /** The value stored in {@code identity.api_key.tier}. */
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Tier of(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
