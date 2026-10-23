-- S3, OFFSET equivalente a pagina 1000 (endpoint de bench GET /bench/offset?page=1000&limit=20).
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT v.numero_controle_pncp AS id, left(v.object_description, 1000) AS object_description, v.organ_name, v.unit_name, v.uf, v.municipality_name, v.ibge_code, v.modality_id, v.status, v.estimated_value, v.proposal_opening_at, v.proposal_closing_at, v.published_at
FROM search.procurement_search_view v
ORDER BY v.published_at DESC, v.numero_controle_pncp DESC LIMIT 20 OFFSET 19980;
