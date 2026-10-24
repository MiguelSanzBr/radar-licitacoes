# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 40 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 10388 | 4711252 | 453.53 | 2013.4 | 218148 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 41981 | 3976850 | 94.73 | 2887.7 | 399981 |
| SELECT websearch_to_tsquery($2, $1)::text | 52649 | 464 | 0.01 | 17.6 | 52649 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 42 | 132 | 3.15 | 42.6 | 42 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 501 | 36 | 0.07 | 7.3 | 0 |
| SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL AND attempts >= $1 | 59 | 12 | 0.20 | 9.8 | 59 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 501 | 8 | 0.02 | 2.2 | 501 |
| SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, a.attnotnull OR (t.typtype = $1 AND t.typnotnull), a. | 39 | 8 | 0.21 | 1.8 | 468 |
| BEGIN | 501 | 8 | 0.02 | 3.0 | 0 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 501 | 6 | 0.01 | 1.3 | 0 |
