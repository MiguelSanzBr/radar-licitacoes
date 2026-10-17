package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.util.Objects;

/** Purchasing unit; the location fields are optional. */
public record OrganUnit(
    String code, String name, Uf uf, String municipalityName, IbgeCode ibgeCode) {

  public OrganUnit {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
  }
}
