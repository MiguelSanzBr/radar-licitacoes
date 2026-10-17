package br.dev.miguelsanzbr.radar.ingest.domain;

import java.util.Objects;

public record Modality(int id, String name) {

  public Modality {
    Objects.requireNonNull(name, "name");
  }
}
