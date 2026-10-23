-- S5: GET /api/v1/procurements/{id}, leitura por chave primaria (JooqDetailReadModel.find).
SELECT numero_controle_pncp AS pid FROM search.procurement_detail_view OFFSET (SELECT count(*) / 2 FROM search.procurement_detail_view) LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT detail::text, etag FROM search.procurement_detail_view WHERE numero_controle_pncp = :'pid';
