package br.dev.miguelsanzbr.radar.ingest.domain;

import java.util.Objects;

public record LegalBasis(int code, String name) {

  public LegalBasis {
    Objects.requireNonNull(name, "name");
  }
}
