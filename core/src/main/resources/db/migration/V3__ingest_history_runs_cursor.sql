CREATE TABLE ingest.procurement_situation_history (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  numero_controle_pncp text NOT NULL,
  from_situation_id smallint, to_situation_id smallint NOT NULL,
  unexpected boolean NOT NULL DEFAULT false,
  pncp_updated_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL
);
CREATE INDEX ON ingest.procurement_situation_history (numero_controle_pncp, recorded_at);

CREATE TABLE ingest.ingestion_run (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind text NOT NULL, modality_id smallint NOT NULL,
  window_start timestamptz NOT NULL, window_end timestamptz NOT NULL,
  cursor_page integer NOT NULL DEFAULT 0, status text NOT NULL,
  fetched integer NOT NULL DEFAULT 0, discovered integer NOT NULL DEFAULT 0,
  updated integer NOT NULL DEFAULT 0, unchanged integer NOT NULL DEFAULT 0,
  started_at timestamptz NOT NULL, finished_at timestamptz, error text
);

CREATE TABLE ingest.ingestion_cursor (
  modality_id smallint PRIMARY KEY,
  watermark timestamptz NOT NULL, overlap interval NOT NULL DEFAULT '15 minutes',
  updated_at timestamptz NOT NULL
);
