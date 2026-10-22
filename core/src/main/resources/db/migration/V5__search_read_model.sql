CREATE TEXT SEARCH CONFIGURATION search.portuguese_unaccent (COPY = pg_catalog.portuguese);
ALTER TEXT SEARCH CONFIGURATION search.portuguese_unaccent
  ALTER MAPPING FOR hword, hword_part, word WITH unaccent, portuguese_stem;

CREATE TABLE search.procurement_search_view (
  numero_controle_pncp text PRIMARY KEY,
  search_vector        tsvector NOT NULL,
  object_description   text NOT NULL,
  organ_name           text NOT NULL,
  unit_name            text,
  uf                   char(2),
  ibge_code            char(7),
  municipality_name    text,
  modality_id          smallint NOT NULL,
  status               text NOT NULL,
  estimated_value      numeric(18,2),
  value_band           smallint NOT NULL,
  proposal_opening_at  timestamptz,
  proposal_closing_at  timestamptz,
  published_at         timestamptz NOT NULL,
  pncp_updated_at      timestamptz NOT NULL,
  source_version       integer NOT NULL,
  projected_at         timestamptz NOT NULL,
  source               text NOT NULL DEFAULT 'pncp'
);

COMMENT ON COLUMN search.procurement_search_view.search_vector IS
  'computed by the projector: A = object, B = organ and unit, C = municipality and modality';
COMMENT ON COLUMN search.procurement_search_view.value_band IS
  '0: no value, 1: under 50k, 2: under 500k, 3: under 5mi, 4: 5mi or more';
COMMENT ON COLUMN search.procurement_search_view.source_version IS
  'aggregateVersion of the event; the projector ignores a lower version';
COMMENT ON COLUMN search.procurement_search_view.source IS 'pncp or synthetic';

CREATE INDEX psv_fts_gin ON search.procurement_search_view USING gin (search_vector);
CREATE INDEX psv_organ_trgm ON search.procurement_search_view USING gin (organ_name gin_trgm_ops);
CREATE INDEX psv_filters_keyset
  ON search.procurement_search_view (uf, modality_id, published_at DESC, numero_controle_pncp DESC);
CREATE INDEX psv_open_closing
  ON search.procurement_search_view (proposal_closing_at) WHERE status = 'PUBLISHED';
CREATE INDEX psv_published_keyset
  ON search.procurement_search_view (published_at DESC, numero_controle_pncp DESC);

CREATE TABLE search.procurement_detail_view (
  numero_controle_pncp text PRIMARY KEY,
  detail               jsonb NOT NULL,
  etag                 text NOT NULL,
  source_version       integer NOT NULL,
  updated_at           timestamptz NOT NULL
);

COMMENT ON COLUMN search.procurement_detail_view.detail IS 'includes the situation history';
COMMENT ON COLUMN search.procurement_detail_view.etag IS 'sha256 of detail';

CREATE TABLE search.procurement_stats_view (
  uf            char(2) NOT NULL,
  modality_id   smallint NOT NULL,
  month         date NOT NULL,
  value_band    smallint NOT NULL,
  count         integer NOT NULL,
  estimated_sum numeric(20,2) NOT NULL,
  PRIMARY KEY (uf, modality_id, month, value_band)
);

CREATE TABLE search.projection_checkpoint (
  projector     text PRIMARY KEY,
  last_event_id uuid,
  last_offset   bigint,
  updated_at    timestamptz NOT NULL
);

CREATE TABLE search.processed_event (
  consumer     text NOT NULL,
  event_id     uuid NOT NULL,
  processed_at timestamptz NOT NULL,
  PRIMARY KEY (consumer, event_id)
);
