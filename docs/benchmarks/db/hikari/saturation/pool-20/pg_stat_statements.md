# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 20 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 10865 | 2346507 | 215.97 | 924.6 | 228165 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 43122 | 1937351 | 44.93 | 1786.7 | 412407 |
| SELECT websearch_to_tsquery($2, $1)::text | 54247 | 369 | 0.01 | 8.3 | 54247 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 43 | 40 | 0.93 | 4.7 | 43 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 503 | 32 | 0.06 | 5.3 | 0 |
| SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, a.attnotnull OR (t.typtype = $1 AND t.typnotnull), a. | 19 | 6 | 0.32 | 2.3 | 228 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 503 | 6 | 0.01 | 0.2 | 503 |
| SELECT to_regclass($1) IS NOT NULL | 240 | 5 | 0.02 | 0.7 | 240 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 503 | 4 | 0.01 | 0.2 | 0 |
| SELECT coalesce(extract($2 FROM now() - ( SELECT created_at FROM outbox.outbox_event WHERE published_at IS NUL | 60 | 2 | 0.04 | 0.1 | 60 |
