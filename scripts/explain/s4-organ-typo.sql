-- S4: GET /api/v1/organs?q=camraa municipal (JooqOrganReadModel.query; o limiar vale so na transacao).
SELECT set_config('pg_trgm.word_similarity_threshold', '0.3', true);
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT organ_name, word_similarity('camraa municipal', organ_name) AS s
FROM search.procurement_search_view
WHERE 'camraa municipal' <% organ_name
GROUP BY organ_name
ORDER BY s DESC, organ_name
LIMIT 10;
