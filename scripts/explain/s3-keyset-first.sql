-- S3, keyset, pagina 1: GET /api/v1/procurements?limit=20 (sem texto: ordem por published_at e id).
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT v.numero_controle_pncp AS id, left(v.object_description, 1000) AS object_description, v.organ_name, v.unit_name, v.uf, v.municipality_name, v.ibge_code, v.modality_id, v.status, v.estimated_value, v.proposal_opening_at, v.proposal_closing_at, v.published_at, NULL::text AS snippet, NULL::float8 AS rank
FROM search.procurement_search_view v
WHERE true
ORDER BY v.published_at DESC, v.numero_controle_pncp DESC LIMIT 21;
