package br.dev.miguelsanzbr.radar.alerts.domain;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a saved search can look at in a procurement: its text, where it is, its modality, value and
 * closing time. Optional facts are null when the notice does not carry them.
 */
public record ProcurementFacts(
    String id,
    String objectDescription,
    String organName,
    String unitName,
    String uf,
    String ibge,
    String municipality,
    Integer modality,
    Money estimatedValue,
    Instant closingAt) {

  public ProcurementFacts {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(objectDescription, "objectDescription");
    Objects.requireNonNull(organName, "organName");
    Objects.requireNonNull(unitName, "unitName");
  }

  /** The text a query is matched against: object, organ, unit and municipality. */
  public String searchableText() {
    return Stream.of(objectDescription, organName, unitName, municipality)
        .filter(Objects::nonNull)
        .collect(Collectors.joining(" "));
  }
}
