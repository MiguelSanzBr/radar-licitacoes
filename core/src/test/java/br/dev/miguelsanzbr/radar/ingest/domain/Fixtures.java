package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/** Test data: a fully populated snapshot that individual tests tweak through {@link Builder}. */
final class Fixtures {

  static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");
  static final ProcurementId ID = new ProcurementId("83021808000182-1-000681/2026");

  private Fixtures() {}

  static final class Builder {
    ProcurementId id = ID;
    PncpSituation situation = new PncpSituation(1, "Divulgada");
    Organ organ = new Organ("83021808000182", "Prefeitura de Exemplo");
    OrganUnit unit =
        new OrganUnit("1010", "Secretaria de Compras", Uf.SC, "Exemplo", new IbgeCode("4205407"));
    Modality modality = new Modality(6, "Pregao Eletronico");
    DisputeMode disputeMode = new DisputeMode(1, "Aberto");
    LegalBasis legalBasis = new LegalBasis(1, "Lei 14.133/2021, Art. 28, I");
    boolean srp = false;
    Money estimatedValue = Money.of("1500.50");
    Money awardedValue = Money.of("1400.00");
    ProposalWindow proposalWindow =
        new ProposalWindow(T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(5)));
    Instant publishedAt = T0.minus(Duration.ofDays(1));
    Instant pncpUpdatedAt = T0.minus(Duration.ofHours(2));
    String objectDescription = "Aquisicao de material de escritorio";
    String rawJson = "{\"numeroControlePNCP\":\"83021808000182-1-000681/2026\"}";

    PncpSnapshot build() {
      return new PncpSnapshot(
          id,
          situation,
          organ,
          unit,
          modality,
          disputeMode,
          legalBasis,
          srp,
          estimatedValue,
          awardedValue,
          proposalWindow,
          publishedAt,
          pncpUpdatedAt,
          objectDescription,
          rawJson);
    }
  }

  static PncpSnapshot snapshot() {
    return snapshot(b -> {});
  }

  static PncpSnapshot snapshot(Consumer<Builder> change) {
    var builder = new Builder();
    change.accept(builder);
    return builder.build();
  }

  static Clock at(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
