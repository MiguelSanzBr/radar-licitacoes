# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 10 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 11474 | 1161883 | 101.26 | 489.8 | 240954 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 46222 | 995429 | 21.54 | 991.6 | 445782 |
| SELECT websearch_to_tsquery($2, $1)::text | 58002 | 232 | 0.00 | 3.7 | 58002 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 507 | 29 | 0.06 | 1.9 | 0 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 45 | 26 | 0.58 | 2.3 | 45 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 507 | 9 | 0.02 | 1.5 | 507 |
| SELECT to_regclass($1) IS NOT NULL | 248 | 4 | 0.02 | 0.7 | 248 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 507 | 4 | 0.01 | 0.3 | 0 |
| SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, a.attnotnull OR (t.typtype = $1 AND t.typnotnull), a. | 9 | 3 | 0.37 | 2.1 | 108 |
| SELECT coalesce(extract($2 FROM now() - ( SELECT created_at FROM outbox.outbox_event WHERE published_at IS NUL | 62 | 2 | 0.04 | 0.2 | 62 |
