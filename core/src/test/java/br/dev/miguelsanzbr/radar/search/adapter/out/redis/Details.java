package br.dev.miguelsanzbr.radar.search.adapter.out.redis;

import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import br.dev.miguelsanzbr.radar.search.application.SituationChange;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import java.util.List;

/** Stored details for the cache tests. */
final class Details {

  static final String ID = "12345678000190-1-000123/2026";

  private Details() {}

  /** Every field filled, with sub-second instants and a history. */
  static ProcurementDetail full(String id, String etag) {
    return new ProcurementDetail(
        id,
        "Aquisição de ambulância equipada",
        "12345678000190",
        "Prefeitura de Teste",
        "1",
        "Secretaria de Saúde",
        "SP",
        "São Paulo",
        "3550308",
        6,
        "Pregão Eletrônico",
        "Aberto",
        "Lei 14.133",
        true,
        "PUBLISHED",
        Money.of("1000.50"),
        Money.of("900.00"),
        Instant.parse("2026-10-20T13:00:00.123456Z"),
        Instant.parse("2026-10-21T12:00:00Z"),
        Instant.parse("2026-10-20T12:00:00Z"),
        Instant.parse("2026-10-20T12:30:00.5Z"),
        List.of(
            new SituationChange(
                null, "Divulgada no PNCP", Instant.parse("2026-10-20T12:00:00Z"), false),
            new SituationChange(
                "Divulgada no PNCP", "Suspensa", Instant.parse("2026-10-20T12:30:00Z"), true)),
        etag);
  }

  /** Only what is mandatory: every optional field is null. */
  static ProcurementDetail sparse(String id, String etag) {
    return new ProcurementDetail(
        id,
        "Objeto",
        "12345678000190",
        "Órgão",
        null,
        null,
        null,
        null,
        null,
        6,
        "Pregão",
        null,
        null,
        false,
        "PUBLISHED",
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        etag);
  }
}
