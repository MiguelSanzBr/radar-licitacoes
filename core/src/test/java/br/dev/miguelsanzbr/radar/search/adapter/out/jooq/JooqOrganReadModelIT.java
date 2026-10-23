package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.discovered;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import br.dev.miguelsanzbr.radar.search.application.OrganMatch;
import br.dev.miguelsanzbr.radar.search.application.OrganSearchUseCase;
import br.dev.miguelsanzbr.radar.search.application.ProcurementView;
import br.dev.miguelsanzbr.radar.search.application.ProjectionStore;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@OutboxSpringBootTest
class JooqOrganReadModelIT {

  @Autowired ProjectionStore store;
  @Autowired OrganSearchUseCase organs;
  @Autowired JdbcTemplate jdbc;

  @Autowired
  @Qualifier("searchDsl")
  DSLContext dsl;

  @Autowired
  @Qualifier("searchTransactionManager")
  PlatformTransactionManager transactionManager;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE search.procurement_search_view, search.procurement_detail_view,"
            + " search.processed_event, search.projection_checkpoint,"
            + " search.procurement_stats_view");
  }

  private void project(int n, String organ) {
    var base = view(id(n), "Objeto " + n, "1000.00", 1);
    var withOrgan =
        new ProcurementView(
            base.id(),
            base.status(),
            base.situation(),
            organ,
            base.unitName(),
            base.uf(),
            base.municipalityName(),
            base.ibgeCode(),
            base.modalityId(),
            base.modalityName(),
            base.estimatedValue(),
            base.proposalOpeningAt(),
            base.proposalClosingAt(),
            base.publishedAt(),
            base.pncpUpdatedAt(),
            base.objectDescription(),
            base.json());
    store.project(discovered("organ-" + n, withOrgan, 1), n);
  }

  private List<String> names(String q, int limit) {
    return organs.search(q, limit).stream().map(OrganMatch::organName).toList();
  }

  @Test
  void aTypoFindsTheOrgan() {
    project(1, "Prefeitura Municipal de Chapecó");
    project(2, "Câmara Municipal de Chapecó");

    var found = organs.search("prefeitra", 10);

    assertThat(found)
        .extracting(OrganMatch::organName)
        .containsExactly("Prefeitura Municipal de Chapecó");
    assertThat(found.get(0).similarity()).isGreaterThan(0.3f);
  }

  @Test
  void anOrganThatManyProcurementsShareAppearsOnce() {
    project(1, "Prefeitura Municipal de Chapecó");
    project(2, "Prefeitura Municipal de Chapecó");
    project(3, "Prefeitura Municipal de Chapecó");

    assertThat(names("prefeitura chapeco", 10)).containsExactly("Prefeitura Municipal de Chapecó");
  }

  @Test
  void theMostSimilarComesFirstAndTheLimitCuts() {
    project(1, "Prefeitura Municipal de Chapecó");
    project(2, "Prefeitura de Chapecó");
    project(3, "Prefeitura Municipal de Chapecó do Sul");

    var found = organs.search("prefeitura de chapecó", 2);

    assertThat(found).hasSize(2);
    assertThat(found.get(0).organName()).isEqualTo("Prefeitura de Chapecó");
    assertThat(found.get(0).similarity()).isGreaterThan(found.get(1).similarity());
  }

  @Test
  void caseIsIgnoredAndAnUnaccentedSpellingStillFindsTheAccentedNameJustLowerRanked() {
    project(1, "SECRETARIA MUNICIPAL DE SAÚDE");

    var accented = organs.search("secretaria municipal de saúde", 10);
    var plain = organs.search("secretaria municipal de saude", 10);

    // why: the index is on organ_name as stored (unaccent is not immutable), so the accent is a
    // different trigram: it costs similarity but, with the rest of the words, the name is found
    assertThat(accented)
        .extracting(OrganMatch::organName)
        .containsExactly("SECRETARIA MUNICIPAL DE SAÚDE");
    assertThat(plain)
        .extracting(OrganMatch::organName)
        .containsExactly("SECRETARIA MUNICIPAL DE SAÚDE");
    assertThat(accented.get(0).similarity()).isGreaterThan(plain.get(0).similarity());
  }

  @Test
  void aTextTooFarFromEveryOrganFindsNothing() {
    project(1, "Prefeitura Municipal de Chapecó");

    assertThat(organs.search("hospital", 10)).isEmpty();
  }

  @Test
  void aHigherThresholdKeepsOnlyCloserNames() {
    project(1, "Prefeitura Municipal de Chapecó");
    var strict = new JooqOrganReadModel(dsl, transactionManager, 0.9);

    assertThat(strict.similarTo("prefeitra", 10)).isEmpty();
    assertThat(strict.similarTo("prefeitura municipal de chapecó", 10)).hasSize(1);
    // the setting is local to the transaction: the default read model is not affected
    assertThat(organs.search("prefeitra", 10)).hasSize(1);
  }

  @Test
  void theTrigramIndexServesTheQuery() {
    jdbc.update(
        """
        INSERT INTO search.procurement_search_view
          (numero_controle_pncp, search_vector, object_description, organ_name, modality_id, status,
           value_band, published_at, pncp_updated_at, source_version, projected_at)
        SELECT 'X' || g, to_tsvector('simple', 'x'), 'x',
               'Prefeitura Municipal de ' || md5(g::text) || ' ' || md5((g * 7)::text),
               6, 'PUBLISHED', 0, now(), now(), 1, now()
        FROM generate_series(1, 20000) AS g
        """);
    jdbc.execute("ANALYZE search.procurement_search_view");
    var plan = new ArrayList<String>();
    var sql =
        "EXPLAIN "
            + new JooqOrganReadModel(dsl, transactionManager, 0.3)
                .query("prefeitra", 10)
                .getSQL(ParamType.INLINED);
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (var statement = connection.createStatement()) {
                // why: 20 thousand rows are cheaper to scan than to probe; the benchmark (2.7)
                // runs the planner's own choice at a million rows
                statement.execute("SET enable_seqscan = off");
                try (var rs = statement.executeQuery(sql)) {
                  while (rs.next()) {
                    plan.add(rs.getString(1));
                  }
                } finally {
                  statement.execute("RESET enable_seqscan");
                }
              }
              return null;
            });
    var text = String.join("\n", plan);

    assertThat(text).contains("psv_organ_trgm");
  }
}
