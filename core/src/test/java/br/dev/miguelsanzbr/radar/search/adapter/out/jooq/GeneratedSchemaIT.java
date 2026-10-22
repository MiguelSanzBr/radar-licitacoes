package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated.Tables.PROCUREMENT_SEARCH_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated.Search;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

@OutboxSpringBootTest
class GeneratedSchemaIT {

  @Autowired
  @Qualifier("searchDsl")
  DSLContext dsl;

  @AfterEach
  void clean() {
    dsl.deleteFrom(PROCUREMENT_SEARCH_VIEW)
        .where(PROCUREMENT_SEARCH_VIEW.NUMERO_CONTROLE_PNCP.like("IT-%"))
        .execute();
  }

  @Test
  void generatesExactlyTheTablesOfTheSearchSchema() {
    assertThat(Search.SEARCH.getTables().stream().map(Table::getName))
        .containsExactlyInAnyOrder(
            "procurement_search_view",
            "procurement_detail_view",
            "procurement_stats_view",
            "projection_checkpoint",
            "processed_event");
  }

  @Test
  void aRowRoundTripsThroughTheGeneratedTableWithTheColumnDefaults() {
    var t = PROCUREMENT_SEARCH_VIEW;
    var now = OffsetDateTime.parse("2026-10-22T12:00:00Z");
    var record = dsl.newRecord(t);
    record.setNumeroControlePncp("IT-GEN");
    // tsvector has no Java type: it is always an expression computed by the database
    record.setSearchVector(DSL.field("to_tsvector('search.portuguese_unaccent', 'Licitação')"));
    record.setObjectDescription("Licitação");
    record.setOrganName("Prefeitura");
    record.setModalityId((short) 6);
    record.setStatus("PUBLISHED");
    record.setValueBand((short) 0);
    record.setPublishedAt(now);
    record.setPncpUpdatedAt(now);
    record.setSourceVersion(1);
    record.setProjectedAt(now);
    dsl.executeInsert(record);

    var row = dsl.selectFrom(t).where(t.NUMERO_CONTROLE_PNCP.eq("IT-GEN")).fetchSingle();

    assertThat(row.getSource()).isEqualTo("pncp");
    assertThat(row.getPublishedAt().toInstant()).isEqualTo(now.toInstant());
    assertThat(row.getUf()).isNull();
  }
}
