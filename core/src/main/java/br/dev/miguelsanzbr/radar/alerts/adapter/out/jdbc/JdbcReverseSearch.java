package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.ReverseSearch;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One statement over every active saved search (ADR-0015). The procurement text becomes a tsvector
 * once, then each query is tested against it with the same text search configuration the search
 * side uses. A filter a procurement cannot answer (no UF, no value) excludes the search; the stored
 * filter keys are those of {@code AlertFilters}.
 */
@Repository
class JdbcReverseSearch implements ReverseSearch {

  private static final String SQL =
      """
      WITH doc AS MATERIALIZED (
        SELECT to_tsvector('search.portuguese_unaccent', :doc) AS vector
      )
      SELECT s.id, s.subscriber_id, s.frequency
      FROM alerts.saved_search s
      JOIN alerts.subscriber u ON u.id = s.subscriber_id
      CROSS JOIN doc
      WHERE s.active
        AND u.verified_at IS NOT NULL
        AND u.deleted_at IS NULL
        AND (s.filters ->> 'uf' IS NULL OR s.filters ->> 'uf' = :uf)
        AND (s.filters ->> 'ibge' IS NULL OR s.filters ->> 'ibge' = :ibge)
        AND (s.filters ->> 'modality' IS NULL
             OR CAST(s.filters ->> 'modality' AS integer) = CAST(:modality AS integer))
        AND (s.filters ->> 'minValue' IS NULL
             OR CAST(s.filters ->> 'minValue' AS numeric) <= CAST(:value AS numeric))
        AND (s.filters ->> 'maxValue' IS NULL
             OR CAST(s.filters ->> 'maxValue' AS numeric) >= CAST(:value AS numeric))
        AND (COALESCE(CAST(s.filters ->> 'open' AS boolean), false) = false
             OR CAST(:closingAt AS timestamptz) > CAST(:now AS timestamptz))
        AND (btrim(s.query_text, E' \\t\\r\\n') = ''
             OR doc.vector @@ websearch_to_tsquery('search.portuguese_unaccent', s.query_text))
      """;

  private final JdbcClient jdbc;

  JdbcReverseSearch(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<SearchMatch> find(ProcurementFacts p, Instant now) {
    var value = p.estimatedValue() == null ? null : p.estimatedValue().amount();
    var sql =
        jdbc.sql(SQL)
            .param("doc", p.searchableText(), Types.VARCHAR)
            .param("uf", p.uf(), Types.VARCHAR)
            .param("ibge", p.ibge(), Types.VARCHAR)
            .param("modality", p.modality(), Types.INTEGER)
            .param("value", value, Types.NUMERIC);
    sql = Db.timestamp(sql, "closingAt", p.closingAt());
    return Db.timestamp(sql, "now", now)
        .query(
            (rs, row) ->
                new SearchMatch(
                    rs.getObject("id", UUID.class),
                    rs.getObject("subscriber_id", UUID.class),
                    Frequency.valueOf(rs.getString("frequency"))))
        .list();
  }
}
