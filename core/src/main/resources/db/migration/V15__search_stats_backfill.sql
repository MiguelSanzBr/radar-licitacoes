-- Databases projected before the stats existed have a full search view and an empty stats table.
-- Runs only when the stats table is empty, so it never doubles a table the projector maintains.
-- Same bucket as the projector: uf 'XX' when unknown, month in America/Sao_Paulo, value as zero
-- when unknown.
INSERT INTO search.procurement_stats_view (uf, modality_id, month, value_band, count, estimated_sum)
SELECT coalesce(uf, 'XX'), modality_id,
       date_trunc('month', published_at AT TIME ZONE 'America/Sao_Paulo')::date, value_band,
       count(*), coalesce(sum(estimated_value), 0)
FROM search.procurement_search_view
WHERE NOT EXISTS (SELECT 1 FROM search.procurement_stats_view)
GROUP BY 1, 2, 3, 4;
