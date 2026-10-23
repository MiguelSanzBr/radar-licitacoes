package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import br.dev.miguelsanzbr.radar.search.application.SituationChange;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Rows and a fixed clock shared by the page tests. */
final class WebFixtures {

  static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
  static final String STORED_ID = "12345678000190-1-000123/2026";
  static final String PATH_ID = "12345678000190-1-000123-2026";

  private WebFixtures() {}

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {
    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  static RankedResult hit(String object, String snippet) {
    return new RankedResult(
        STORED_ID,
        object,
        snippet,
        "Prefeitura de Teste",
        null,
        "SP",
        "São Paulo",
        "3550308",
        6,
        "PUBLISHED",
        Money.of("1500"),
        NOW.minusSeconds(3600),
        NOW.plusSeconds(3600),
        NOW.minusSeconds(7200),
        0.25);
  }

  static ProcurementDetail detail(String id, String object) {
    return new ProcurementDetail(
        id,
        object,
        "12345678000190",
        "Prefeitura de Teste",
        "1",
        "Secretaria de Saúde",
        "SP",
        "São Paulo",
        "3550308",
        6,
        "Pregão - Eletrônico",
        "Aberto",
        "Lei 14.133/2021",
        true,
        "SUSPENDED",
        Money.of("1500"),
        null,
        NOW.plusSeconds(60),
        NOW.plusSeconds(3600),
        NOW.minusSeconds(7200),
        NOW.minusSeconds(60),
        List.of(
            new SituationChange(null, "Divulgada no PNCP", NOW.minusSeconds(7200), false),
            new SituationChange("Divulgada no PNCP", "Suspensa", NOW.minusSeconds(60), true)),
        "abc123");
  }
}
