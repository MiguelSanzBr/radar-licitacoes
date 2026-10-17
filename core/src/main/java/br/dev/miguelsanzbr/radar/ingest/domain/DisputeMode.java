package br.dev.miguelsanzbr.radar.ingest.domain;

import java.util.Objects;

public record DisputeMode(int id, String name) {

  public DisputeMode {
    Objects.requireNonNull(name, "name");
  }
}
