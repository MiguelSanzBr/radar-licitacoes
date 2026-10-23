-- S1, termo comum: GET /api/v1/procurements?q=contratação (esta forma e a de JooqSearchReadModel.ranked).
-- O texto vira tsquery num primeiro comando e entra na consulta como valor; aqui entra como literal.
\if :{?cap}
\else
  \set cap 2000
\endif
\set term 'contratação'
SELECT websearch_to_tsquery('search.portuguese_unaccent', :'term')::text AS tsq \gset
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT v.numero_controle_pncp AS id, left(v.object_description, 1000) AS object_description, v.organ_name, v.unit_name, v.uf, v.municipality_name, v.ibge_code, v.modality_id, v.status, v.estimated_value, v.proposal_opening_at, v.proposal_closing_at, v.published_at,
       page.rank,
       ts_headline('search.portuguese_unaccent', left(v.object_description, 100000), :'tsq'::tsquery,
                   'MaxFragments=2, MaxWords=20, MinWords=5, StartSel=<b>, StopSel=</b>') AS snippet
FROM (SELECT c.id, c.rank
      FROM (SELECT v.numero_controle_pncp AS id, ts_rank_cd(v.search_vector, :'tsq'::tsquery)::float8 AS rank
            FROM search.procurement_search_view v
            WHERE v.search_vector @@ :'tsq'::tsquery AND true
            ORDER BY v.published_at DESC, v.numero_controle_pncp DESC LIMIT :cap) c
      ORDER BY c.rank DESC, c.id DESC LIMIT 21) page
JOIN search.procurement_search_view v ON v.numero_controle_pncp = page.id
ORDER BY page.rank DESC, page.id DESC;
