-- One row per organ name, kept by the projector next to the search view: the typo search of
-- organs reads this small table instead of scanning a million-row view (ADR-0011).
CREATE TABLE search.organ (
  organ_name        text PRIMARY KEY,
  procurement_count integer NOT NULL
);

CREATE INDEX organ_name_trgm ON search.organ USING gin (organ_name gin_trgm_ops);

-- Databases projected before this table existed have a full search view and no organs. The guard
-- (only when empty) is not what makes this safe to run once: Flyway runs the migration once. If the
-- previous release still projects between this migration and the new code, the table falls behind
-- the view; rebuild it with TRUNCATE and this INSERT again (ADR-0010, ADR-0011).
INSERT INTO search.organ (organ_name, procurement_count)
SELECT organ_name, count(*)
FROM search.procurement_search_view
WHERE NOT EXISTS (SELECT 1 FROM search.organ)
GROUP BY organ_name;
