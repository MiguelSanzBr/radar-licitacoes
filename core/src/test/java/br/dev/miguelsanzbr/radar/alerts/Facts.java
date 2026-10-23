package br.dev.miguelsanzbr.radar.alerts;

import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;

/** A procurement as the matcher sees it, with one-field variations for the filter tests. */
public final class Facts {

  public static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  public static final String ID = "83021808000182-1-000001/2026";

  private String id = ID;
  private String objectDescription = "Aquisicao de notebooks para a secretaria de educacao";
  private String organName = "Prefeitura de Exemplo";
  private String unitName = "Secretaria de Compras";
  private String uf = "SC";
  private String ibge = "4205407";
  private String municipality = "Exemplo";
  private Integer modality = 6;
  private Money estimatedValue = Money.of("1500.50");
  private Instant closingAt = NOW.plusSeconds(4 * 86_400);

  private Facts() {}

  public static Facts procurement() {
    return new Facts();
  }

  public Facts id(String value) {
    this.id = value;
    return this;
  }

  public Facts description(String value) {
    this.objectDescription = value;
    return this;
  }

  public Facts uf(String value) {
    this.uf = value;
    return this;
  }

  public Facts ibge(String value) {
    this.ibge = value;
    return this;
  }

  public Facts modality(Integer value) {
    this.modality = value;
    return this;
  }

  public Facts value(String amount) {
    this.estimatedValue = amount == null ? null : Money.of(amount);
    return this;
  }

  public Facts closingAt(Instant value) {
    this.closingAt = value;
    return this;
  }

  public ProcurementFacts build() {
    return new ProcurementFacts(
        id,
        objectDescription,
        organName,
        unitName,
        uf,
        ibge,
        municipality,
        modality,
        estimatedValue,
        closingAt);
  }
}
