CREATE TABLE ingest.procurement (
  numero_controle_pncp  text        NOT NULL,
  published_at          timestamptz NOT NULL,          -- chave de partição
  content_hash          bytea       NOT NULL,
  version               integer     NOT NULL DEFAULT 0,
  pncp_situation_id     smallint    NOT NULL,
  pncp_situation_name   text        NOT NULL,
  status                text        NOT NULL,           -- PUBLISHED|SUSPENDED|REVOKED|ANNULLED
  object_description    text        NOT NULL,
  organ_cnpj            char(14)    NOT NULL,
  organ_name            text        NOT NULL,
  unit_code             text,
  unit_name             text,
  uf                    char(2),
  municipality_name     text,
  ibge_code             char(7),
  modality_id           smallint    NOT NULL,
  modality_name         text        NOT NULL,
  dispute_mode_id       smallint,
  legal_basis_id        integer,
  srp                   boolean     NOT NULL DEFAULT false,
  estimated_value       numeric(18,2),
  awarded_value         numeric(18,2),
  proposal_opening_at   timestamptz,
  proposal_closing_at   timestamptz,
  pncp_updated_at       timestamptz NOT NULL,
  closed_emitted_at     timestamptz,
  raw                   jsonb       NOT NULL,
  first_seen_at         timestamptz NOT NULL,
  last_seen_at          timestamptz NOT NULL,
  -- Acréscimos ao §3.4: o agregado restaura nome do modo de disputa, nome do enquadramento legal
  -- e o contador de revisão (versão do envelope de evento), que as colunas acima não guardam.
  dispute_mode_name     text,
  legal_basis_name      text,
  revision              bigint      NOT NULL DEFAULT 1,
  PRIMARY KEY (numero_controle_pncp, published_at)
) PARTITION BY RANGE (published_at);

CREATE INDEX ON ingest.procurement (pncp_updated_at);
CREATE INDEX ON ingest.procurement (proposal_closing_at) WHERE status = 'PUBLISHED' AND closed_emitted_at IS NULL;

-- findById(numero_controle_pncp) não conhece o published_at: a PK (numero_controle_pncp,
-- published_at), um btree por partição, atende a igualdade pela coluna líder, sem índice extra.

-- Cria a partição mensal (limites em UTC) se ainda não existir e devolve o nome qualificado.
-- Sem partição DEFAULT de propósito: mês sem partição faz o insert falhar alto.
CREATE FUNCTION ingest.ensure_partition(month date) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  first_day date := date_trunc('month', month)::date;
  name text := format('procurement_y%sm%s', to_char(first_day, 'YYYY'), to_char(first_day, 'MM'));
BEGIN
  EXECUTE format(
    'CREATE TABLE IF NOT EXISTS ingest.%I PARTITION OF ingest.procurement FOR VALUES FROM (%L) TO (%L)',
    name,
    (first_day::timestamp AT TIME ZONE 'UTC'),
    ((first_day + interval '1 month')::timestamp AT TIME ZONE 'UTC'));
  RETURN 'ingest.' || name;
END $$;

SELECT ingest.ensure_partition(make_date(y, m, 1))
FROM generate_series(2024, 2027) AS y, generate_series(1, 12) AS m;
