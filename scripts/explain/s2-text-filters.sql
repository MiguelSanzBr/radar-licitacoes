-- S2: busca com UF, modalidade e open=true (filtros de JooqSearchReadModel.filters, now() como na aplicacao).
\set term 'contratação'
SELECT websearch_to_tsquery('search.portuguese_unaccent', :'term')::text AS tsq \gset
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT page.id, page.object_description, page.organ_name, page.unit_name, page.uf, page.municipality_name,
       page.ibge_code, page.modality_id, page.status, page.estimated_value, page.proposal_opening_at,
       page.proposal_closing_at, page.published_at, page.rank,
       ts_headline('search.portuguese_unaccent', left(page.full_object, 100000), :'tsq'::tsquery,
                   'MaxFragments=2, MaxWords=20, MinWords=5, StartSel=<b>, StopSel=</b>') AS snippet
FROM (SELECT v.numero_controle_pncp AS id, left(v.object_description, 1000) AS object_description, v.organ_name, v.unit_name, v.uf, v.municipality_name, v.ibge_code, v.modality_id, v.status, v.estimated_value, v.proposal_opening_at, v.proposal_closing_at, v.published_at,
             v.object_description AS full_object, ts_rank_cd(v.search_vector, :'tsq'::tsquery)::float8 AS rank
      FROM search.procurement_search_view v
      WHERE v.search_vector @@ :'tsq'::tsquery AND true AND v.uf = 'SP'::bpchar AND v.modality_id = 6
        AND v.status = 'PUBLISHED' AND v.proposal_closing_at > now()
        AND (v.proposal_opening_at IS NULL OR v.proposal_opening_at <= now())
      ORDER BY rank DESC, id DESC LIMIT 21) page
ORDER BY page.rank DESC, page.id DESC;
