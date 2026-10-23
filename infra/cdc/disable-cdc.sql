-- Desliga o CDC do outbox (ADR-0018): o par de enable-cdc.sql. Idempotente. Pare o conector antes:
-- um slot ativo faz o script falhar de proposito, para nao tirar a publicacao de baixo dele.
--
-- Acoplamento de nomes: o slot e a publicacao se chamam radar_outbox aqui, em enable-cdc.sql, em
-- slot.name e publication.name de connector.properties e em radar.outbox.slot-name (o medidor do
-- slot). Renomear um exige renomear todos; um nome divergente deixa o slot velho retendo WAL.
BEGIN;

DO $do$
BEGIN
  IF EXISTS (SELECT FROM pg_replication_slots WHERE slot_name = 'radar_outbox' AND active) THEN
    RAISE EXCEPTION 'o slot radar_outbox esta ativo: pare o conector antes de desligar o CDC';
  END IF;
END
$do$;

-- Um slot sem conector retem WAL para sempre.
SELECT pg_drop_replication_slot(slot_name)
  FROM pg_replication_slots WHERE slot_name = 'radar_outbox' AND NOT active;

DROP PUBLICATION IF EXISTS radar_outbox;
DROP TABLE IF EXISTS outbox.cdc_heartbeat;
ALTER TABLE outbox.outbox_event DROP COLUMN IF EXISTS envelope;
DROP FUNCTION IF EXISTS outbox.build_envelope(uuid, text, smallint, text, text, jsonb, jsonb);

COMMIT;
