package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.ingest.domain.DisputeMode;
import br.dev.miguelsanzbr.radar.ingest.domain.LegalBasis;
import br.dev.miguelsanzbr.radar.ingest.domain.Modality;
import br.dev.miguelsanzbr.radar.ingest.domain.Organ;
import br.dev.miguelsanzbr.radar.ingest.domain.OrganUnit;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSituation;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.ingest.domain.ProposalWindow;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Test data for the persistence tests: a fully populated notice published in October 2026. */
final class ProcurementFixtures {

  static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");
  static final Instant PUBLISHED_AT = Instant.parse("2026-10-21T12:00:00Z");
  static final String RAW = "{\"b\": [1, 2], \"a\": {\"x\": null}}";

  private ProcurementFixtures() {}

  static ProcurementId id(int sequence) {
    return new ProcurementId("83021808000182-1-%06d/2026".formatted(sequence));
  }

  static PncpSnapshot snapshot(ProcurementId id, int situationId, String objectDescription) {
    return new PncpSnapshot(
        id,
        new PncpSituation(situationId, "Situacao " + situationId),
        new Organ("83021808000182", "Prefeitura de Exemplo"),
        new OrganUnit("1010", "Secretaria de Compras", Uf.SC, "Exemplo", new IbgeCode("4205407")),
        new Modality(6, "Pregao Eletronico"),
        new DisputeMode(1, "Aberto"),
        new LegalBasis(1, "Lei 14.133/2021, Art. 28, I"),
        true,
        Money.of("1500.5"),
        Money.of("1400"),
        new ProposalWindow(T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(5))),
        PUBLISHED_AT,
        T0.minus(Duration.ofHours(2)),
        objectDescription,
        RAW);
  }

  static PncpSnapshot snapshot(ProcurementId id) {
    return snapshot(id, 1, "Aquisicao de material de escritorio");
  }

  static PncpSnapshot withPublishedAt(PncpSnapshot s, Instant publishedAt) {
    return new PncpSnapshot(
        s.id(),
        s.situation(),
        s.organ(),
        s.unit(),
        s.modality(),
        s.disputeMode(),
        s.legalBasis(),
        s.srp(),
        s.estimatedValue(),
        s.awardedValue(),
        s.proposalWindow(),
        publishedAt,
        s.pncpUpdatedAt(),
        s.objectDescription(),
        s.rawJson());
  }

  /** Same notice with every optional field absent. */
  static PncpSnapshot bare(ProcurementId id) {
    var full = snapshot(id);
    return new PncpSnapshot(
        id,
        full.situation(),
        full.organ(),
        new OrganUnit("1010", "Secretaria de Compras", null, null, null),
        full.modality(),
        null,
        null,
        false,
        null,
        null,
        null,
        full.publishedAt(),
        full.pncpUpdatedAt(),
        "",
        "{}");
  }

  static Procurement discovered(PncpSnapshot snapshot) {
    return Procurement.discover(snapshot, clockAt(T0)).procurement();
  }

  static Clock clockAt(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
