-- Passo "expand" da troca de ingest.procurement.estimated_value (numeric) por
-- estimated_value_cents (bigint), sem downtime (docs/adr/0010, secao expand-contract).
-- A coluna nova e nula e sem default: no PG 18 o ADD COLUMN so mexe no catalogo e propaga para
-- as particoes. As copias de leitura (search, alerts) nao entram nesta troca.
-- O ALTER pede trava exclusiva: atras de uma transacao longa ele enfileira e todas as consultas
-- seguintes enfileiram atras dele. Melhor falhar em 5 s e repetir o deploy.
SET LOCAL lock_timeout = '5s';

ALTER TABLE ingest.procurement ADD COLUMN estimated_value_cents bigint;

-- Dupla escrita nos dois sentidos: a release anterior so conhece estimated_value e a atual grava
-- so ele; quem mexer em uma coluna move a outra. Mudou as duas e elas divergem: falha alto.
CREATE FUNCTION ingest.sync_estimated_value() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  value_changed boolean;
  cents_changed boolean;
BEGIN
  IF TG_OP = 'INSERT' THEN
    value_changed := NEW.estimated_value IS NOT NULL;
    cents_changed := NEW.estimated_value_cents IS NOT NULL;
  ELSE
    value_changed := NEW.estimated_value IS DISTINCT FROM OLD.estimated_value;
    cents_changed := NEW.estimated_value_cents IS DISTINCT FROM OLD.estimated_value_cents;
  END IF;

  IF value_changed AND cents_changed THEN
    IF NEW.estimated_value_cents IS DISTINCT FROM round(NEW.estimated_value * 100) THEN
      RAISE EXCEPTION 'estimated_value % and estimated_value_cents % disagree',
        NEW.estimated_value, NEW.estimated_value_cents
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF value_changed THEN
    NEW.estimated_value_cents := round(NEW.estimated_value * 100);
  ELSIF cents_changed THEN
    NEW.estimated_value := NEW.estimated_value_cents / 100.0;
  ELSIF NEW.estimated_value_cents IS NULL AND NEW.estimated_value IS NOT NULL THEN
    -- cura a linha de antes do expand que uma escrita qualquer tocou
    NEW.estimated_value_cents := round(NEW.estimated_value * 100);
  END IF;
  RETURN NEW;
END $$;

-- Na tabela particionada o gatilho de linha vale para todas as particoes, as de hoje e as que
-- ingest.ensure_partition criar depois (PG 13 ou mais).
CREATE TRIGGER sync_estimated_value
  BEFORE INSERT OR UPDATE ON ingest.procurement
  FOR EACH ROW EXECUTE FUNCTION ingest.sync_estimated_value();

-- Linhas que o backfill ainda precisa preencher, na ordem da chave: o "sobrou alguma?" e a escolha
-- de cada lote leem so este indice, e depois do backfill ele fica vazio (o gatilho ja preenche as
-- linhas novas), entao o job parado custa quase nada. Indice no pai particionado propaga para as
-- particoes; a construcao segura escritas, e o custo cresce com as linhas pendentes.
CREATE INDEX procurement_cents_pending_idx
  ON ingest.procurement (numero_controle_pncp, published_at)
  WHERE estimated_value_cents IS NULL AND estimated_value IS NOT NULL;
