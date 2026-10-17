package br.dev.miguelsanzbr.radar.ingest.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Contracting organ; {@code cnpj} is 14 digits. */
public record Organ(String cnpj, String name) {

  private static final Pattern CNPJ = Pattern.compile("^\\d{14}$");

  public Organ {
    if (cnpj == null || !CNPJ.matcher(cnpj).matches()) {
      throw new IllegalArgumentException("invalid cnpj [" + cnpj + "]");
    }
    Objects.requireNonNull(name, "name");
  }
}
