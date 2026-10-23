-- S6: GET /api/v1/stats?groupBy=UF (JooqStatsReadModel.aggregate; nunca le a view de busca).
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT uf::text AS k, sum(count)::bigint AS c, sum(estimated_sum) AS s
FROM search.procurement_stats_view GROUP BY uf ORDER BY uf;
