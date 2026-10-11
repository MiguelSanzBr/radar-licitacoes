package br.dev.miguelsanzbr.radar.shared;

import java.util.Locale;

/** The 27 Brazilian federative units. */
public enum Uf {
  AC,
  AL,
  AP,
  AM,
  BA,
  CE,
  DF,
  ES,
  GO,
  MA,
  MT,
  MS,
  MG,
  PA,
  PB,
  PR,
  PE,
  PI,
  RJ,
  RN,
  RS,
  RO,
  RR,
  SC,
  SP,
  SE,
  TO;

  public static Uf of(String sigla) {
    if (sigla != null) {
      var wanted = sigla.trim().toUpperCase(Locale.ROOT);
      for (var uf : values()) {
        if (uf.name().equals(wanted)) {
          return uf;
        }
      }
    }
    throw new IllegalArgumentException("invalid uf [" + sigla + "]");
  }
}
