-- S3, keyset, pagina 1000: o cursor e a chave da ultima linha da pagina 999.
SELECT v.published_at AS cur_p, v.numero_controle_pncp AS cur_id
FROM search.procurement_search_view v
ORDER BY v.published_at DESC, v.numero_controle_pncp DESC OFFSET 19979 LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT v.numero_controle_pncp AS id, left(v.object_description, 1000) AS object_description, v.organ_name, v.unit_name, v.uf, v.municipality_name, v.ibge_code, v.modality_id, v.status, v.estimated_value, v.proposal_opening_at, v.proposal_closing_at, v.published_at, NULL::text AS snippet, NULL::float8 AS rank
FROM search.procurement_search_view v
WHERE true AND (v.published_at, v.numero_controle_pncp) < (:'cur_p'::timestamptz, :'cur_id')
ORDER BY v.published_at DESC, v.numero_controle_pncp DESC LIMIT 21;
