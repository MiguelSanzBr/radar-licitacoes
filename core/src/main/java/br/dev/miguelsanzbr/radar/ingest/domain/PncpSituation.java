package br.dev.miguelsanzbr.radar.ingest.domain;

import java.util.Objects;

public record PncpSituation(int id, String name) {

  public PncpSituation {
    Objects.requireNonNull(name, "name");
  }
}
