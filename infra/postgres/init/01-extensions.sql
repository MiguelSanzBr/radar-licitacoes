-- Roda uma vez, no primeiro start do volume. pg_stat_statements precisa de shared_preload_libraries
-- (configurado no compose); as demais extensoes (unaccent, pg_trgm, citext) vem da migracao V1.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pgstattuple;
