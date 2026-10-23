-- Liga o CDC do outbox (ADR-0018). Idempotente: pode rodar de novo sem efeito. Nao faz parte do
-- Flyway de proposito: no modo polling (o padrao) a tabela fica como esta, sem coluna extra.
--
-- Exige PostgreSQL 18: uma coluna gerada so entra numa lista de colunas de publicacao a partir da
-- versao 18 (no 17 o CREATE PUBLICATION falha com "cannot use generated column in publication
-- column list", verificado na 17.11). O script confere a versao antes de mexer em qualquer coisa e
-- roda numa transacao so, entao uma falha nao deixa a coluna pela metade. Tambem exige
-- wal_level=logical no servidor (reinicio) para o slot funcionar.
--
-- Cuidado: ADD COLUMN ... STORED reescreve a tabela com ACCESS EXCLUSIVE; a tabela e uma fila
-- curta (retencao de 7 dias), entao o travamento dura segundos.

BEGIN;

DO $do$
BEGIN
  IF current_setting('server_version_num')::int < 180000 THEN
    RAISE EXCEPTION 'o CDC do outbox exige PostgreSQL 18 (este e o %)', current_setting('server_version');
  END IF;
END
$do$;

-- IMMUTABLE vale para estes tipos de argumento (text, numeros, jsonb); jsonb_build_object so e
-- STABLE porque aceita qualquer tipo, e uma coluna gerada exige expressao imutavel.
CREATE OR REPLACE FUNCTION outbox.build_envelope(
  event_id uuid, event_type text, schema_version smallint, aggregate_type text,
  aggregate_id text, headers jsonb, payload jsonb)
RETURNS jsonb
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN jsonb_build_object(
  'eventId', event_id::text,
  'eventType', event_type,
  'schemaVersion', schema_version,
  'aggregateType', aggregate_type,
  'aggregateId', aggregate_id,
  'aggregateVersion', headers -> 'aggregateVersion',
  'occurredAt', headers -> 'occurredAt',
  'traceparent', headers -> 'traceparent',
  'payload', payload);

-- O envelope que o relay monta a partir das outras colunas, calculado pelo banco.
ALTER TABLE outbox.outbox_event
  ADD COLUMN IF NOT EXISTS envelope jsonb GENERATED ALWAYS AS (
    outbox.build_envelope(
      event_id, event_type, schema_version, aggregate_type, aggregate_id, headers, payload)
  ) STORED;

-- Tabela do batimento (heartbeat.action.query do conector): sem escrita numa tabela publicada, o slot
-- nao avanca enquanto o outbox esta parado e outras tabelas recebem escrita, e o WAL delas fica
-- retido. So recebe INSERT (a limpeza no mesmo comando); DELETE nao e publicado.
CREATE TABLE IF NOT EXISTS outbox.cdc_heartbeat (beat timestamptz NOT NULL);

-- So INSERT (os UPDATE do relay e os DELETE da limpeza nunca chegam ao slot) e so as colunas que o
-- roteador le, para o conector nao decodificar payload e headers de novo. O slot nasce no primeiro
-- start do conector (ou antes, a mao, ver o runbook); sem slot a publicacao nao custa nada.
DO $do$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_publication WHERE pubname = 'radar_outbox') THEN
    CREATE PUBLICATION radar_outbox
      FOR TABLE outbox.outbox_event (
        id, event_id, aggregate_type, aggregate_id, event_type, schema_version, envelope),
      outbox.cdc_heartbeat
      WITH (publish = 'insert');
  END IF;
END
$do$;

COMMIT;
