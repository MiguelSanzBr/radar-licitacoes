package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.situationChanged;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import br.dev.miguelsanzbr.radar.search.application.SituationChange;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@OutboxSpringBootTest
class JooqDetailReadModelIT {

  private static final String ID = "12345678000190-1-000123/2026";

  @Autowired ProjectionStore store;
  @Autowired JooqDetailReadModel readModel;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void truncate() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint");
  }

  @Test
  void aProjectedProcurementComesBackWithItsHistoryAndEtag() {
    store.project(discovered("d", view(ID, "Aquisição de computadores", "1500.00", 1), 1), 0);
    store.project(situationChanged("s", ID, 2, 1, 4), 1);

    var detail = readModel.find(ID).orElseThrow();

    assertThat(detail.id()).isEqualTo(ID);
    assertThat(detail.objectDescription()).isEqualTo("Aquisição de computadores");
    assertThat(detail.organCnpj()).isEqualTo("12345678000190");
    assertThat(detail.organName()).isEqualTo("Prefeitura de Teste");
    assertThat(detail.unitCode()).isEqualTo("1");
    assertThat(detail.unitName()).isEqualTo("Secretaria de Saúde");
    assertThat(detail.uf()).isEqualTo("SP");
    assertThat(detail.municipalityName()).isEqualTo("São Paulo");
    assertThat(detail.ibgeCode()).isEqualTo("3550308");
    assertThat(detail.modalityId()).isEqualTo(6);
    assertThat(detail.modalityName()).isEqualTo("Pregão Eletrônico");
    assertThat(detail.disputeMode()).isNull();
    assertThat(detail.legalBasis()).isNull();
    assertThat(detail.srp()).isFalse();
    assertThat(detail.status()).isEqualTo("SUSPENDED");
    assertThat(detail.estimatedValue()).isEqualTo(Money.of("1500.00"));
    assertThat(detail.awardedValue()).isNull();
    assertThat(detail.proposalOpeningAt()).isEqualTo(Instant.parse("2026-10-20T13:00:00Z"));
    assertThat(detail.proposalClosingAt()).isEqualTo(Instant.parse("2026-10-21T12:00:00Z"));
    assertThat(detail.publishedAt()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
    assertThat(detail.pncpUpdatedAt()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
    assertThat(detail.history())
        .containsExactly(
            new SituationChange(
                null, "Divulgada no PNCP", Instant.parse("2026-10-20T12:00:00Z"), false),
            new SituationChange(
                "Divulgada no PNCP", "Suspensa", Instant.parse("2026-10-20T12:01:00Z"), false));
    assertThat(detail.etag())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT etag FROM search.procurement_detail_view WHERE numero_controle_pncp = ?",
                String.class,
                ID));
  }

  @Test
  void optionalPartsOfTheSnapshotAreCarriedWhenPresent() {
    jdbc.update(
        "INSERT INTO search.procurement_detail_view VALUES (?, ?::jsonb, 'e1', 1, now())",
        ID,
        """
        {"id": "12345678000190-1-000123/2026", "status": "PUBLISHED",
         "organ": {"cnpj": "12345678000190", "name": "Órgão"},
         "unit": {"code": "9", "name": null, "uf": null, "municipalityName": null,
                  "ibgeCode": null},
         "modality": {"id": 8, "name": "Dispensa"},
         "disputeMode": {"id": 1, "name": "Aberto"},
         "legalBasis": {"code": 5, "name": "Lei 14.133/2021, Art. 75"},
         "srp": true, "estimatedValue": null, "awardedValue": "99.90", "proposalWindow": null,
         "publishedAt": "2026-10-20T12:00:00Z", "pncpUpdatedAt": "2026-10-20T12:00:00Z",
         "objectDescription": "Objeto", "history": []}
        """);

    var detail = readModel.find(ID).orElseThrow();

    assertThat(detail.disputeMode()).isEqualTo("Aberto");
    assertThat(detail.legalBasis()).isEqualTo("Lei 14.133/2021, Art. 75");
    assertThat(detail.srp()).isTrue();
    assertThat(detail.estimatedValue()).isNull();
    assertThat(detail.awardedValue()).isEqualTo(Money.of("99.90"));
    assertThat(detail.proposalOpeningAt()).isNull();
    assertThat(detail.proposalClosingAt()).isNull();
    assertThat(detail.unitName()).isNull();
    assertThat(detail.history()).isEmpty();
    assertThat(detail.etag()).isEqualTo("e1");
  }

  @Test
  void anUnprojectedIdIsEmpty() {
    assertThat(readModel.find(ID)).isEmpty();
  }
}
