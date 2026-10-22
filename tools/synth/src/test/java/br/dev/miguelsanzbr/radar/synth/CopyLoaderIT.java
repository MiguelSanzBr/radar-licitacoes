package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CopyLoaderIT {

  private static Corpus corpus;

  @BeforeAll
  static void load() {
    corpus = Corpus.load(List.of(Fixtures.SEED));
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        SharedPostgres.INSTANCE.getJdbcUrl(),
        SharedPostgres.INSTANCE.getUsername(),
        SharedPostgres.INSTANCE.getPassword());
  }

  private static long count(String sql) throws SQLException {
    try (var c = connect();
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long load(Corpus from, long seed, long count, int batch) throws Exception {
    try (var c = connect()) {
      return CopyLoader.load(c, new Generator(from, seed, count), count, batch);
    }
  }

  private static String checksum() throws SQLException {
    try (var c = connect();
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                """
                SELECT md5(string_agg(concat_ws('|', v.numero_controle_pncp, v.search_vector::text,
                  v.object_description, v.organ_name, v.unit_name, v.uf, v.ibge_code,
                  v.municipality_name, v.modality_id, v.status, v.estimated_value, v.value_band,
                  v.proposal_opening_at, v.proposal_closing_at, v.published_at, v.pncp_updated_at,
                  d.detail::text, d.etag), E'\\n' ORDER BY v.numero_controle_pncp))
                FROM search.procurement_search_view v
                JOIN search.procurement_detail_view d USING (numero_controle_pncp)
                """)) {
      rs.next();
      return rs.getString(1);
    }
  }

  @BeforeEach
  void empty() throws SQLException {
    try (var c = connect();
        var s = c.createStatement()) {
      s.execute("TRUNCATE search.procurement_search_view, search.procurement_detail_view");
    }
  }

  @Test
  void loadsTenThousandLabelledRowsInBatches() throws Exception {
    assertThat(load(corpus, 42, 10_000, 3_000)).isEqualTo(10_000);

    assertThat(count("SELECT count(*) FROM search.procurement_search_view")).isEqualTo(10_000);
    assertThat(
            count(
                "SELECT count(*) FROM search.procurement_search_view WHERE source = 'synthetic'"
                    + " AND numero_controle_pncp ~ '^SYN-[0-9]{14}-1-[0-9]{6}/[0-9]{4}$'"))
        .isEqualTo(10_000);
    assertThat(count("SELECT count(*) FROM search.procurement_detail_view")).isEqualTo(10_000);
  }

  @Test
  void theVectorIsTheOneOfTheProjectorAndMatchesFrequentWords() throws Exception {
    load(corpus, 42, 10_000, 4_000);

    assertThat(
            count(
                """
                SELECT count(*) FROM search.procurement_search_view
                WHERE search_vector IS DISTINCT FROM (
                  setweight(to_tsvector('search.portuguese_unaccent', left(object_description, 100000)), 'A')
                  || setweight(to_tsvector('search.portuguese_unaccent', organ_name || ' ' || coalesce(unit_name, '')), 'B')
                  || setweight(to_tsvector('search.portuguese_unaccent', coalesce(municipality_name, '') || ' '
                       || (CASE modality_id WHEN 6 THEN 'Pregão - Eletrônico' ELSE '' END)), 'C'))
                """))
        .isZero();
    assertThat(
            count("SELECT count(*) FROM search.procurement_search_view WHERE search_vector = ''"))
        .isZero();
    long matching =
        count(
            "SELECT count(*) FROM search.procurement_search_view WHERE search_vector @@"
                + " websearch_to_tsquery('search.portuguese_unaccent', 'contratação')");
    assertThat(matching).isBetween(3_000L, 10_000L);
    assertThat(
            count(
                "SELECT count(*) FROM search.procurement_search_view WHERE search_vector @@"
                    + " to_tsquery('search.portuguese_unaccent', 'municipal:B')"))
        .isPositive();
  }

  @Test
  void theDetailCarriesTheSnapshotHistoryAndTheEtagOfTheStoredJson() throws Exception {
    load(corpus, 42, 500, 200);

    assertThat(
            count(
                """
                SELECT count(*) FROM search.procurement_detail_view d
                JOIN search.procurement_search_view v USING (numero_controle_pncp)
                WHERE d.detail->>'id' = d.numero_controle_pncp
                  AND d.detail->>'objectDescription' = v.object_description
                  AND jsonb_array_length(d.detail->'history') = 1
                  AND d.etag = encode(sha256(convert_to(d.detail::text, 'UTF8')), 'hex')
                  AND d.source_version = 1 AND v.source_version = 1
                """))
        .isEqualTo(500);
  }

  @Test
  void sameSeedGivesTheSameRowsAndARerunInsertsNothing() throws Exception {
    load(corpus, 42, 2_000, 700);
    var first = checksum();

    assertThat(load(corpus, 42, 2_000, 700)).isZero();
    assertThat(checksum()).isEqualTo(first);

    empty();
    load(corpus, 42, 2_000, 1_000);
    assertThat(checksum()).isEqualTo(first);

    empty();
    load(corpus, 43, 2_000, 700);
    assertThat(checksum()).isNotEqualTo(first);
  }

  @Test
  void absentValuesAndPlacesAreStoredAsNull() throws Exception {
    load(Corpus.load(List.of(Fixtures.EDGE)), 5, 300, 100);

    assertThat(
            count(
                "SELECT count(*) FROM search.procurement_search_view WHERE estimated_value IS NULL"
                    + " AND value_band = 0 AND uf IS NULL AND ibge_code IS NULL"
                    + " AND municipality_name IS NULL AND proposal_opening_at IS NULL"))
        .isPositive();
    assertThat(
            count(
                "SELECT count(*) FROM search.procurement_search_view WHERE object_description"
                    + " LIKE '%\"%'"))
        .isPositive();
  }
}
