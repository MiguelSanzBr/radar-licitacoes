# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 4 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 10017 | 467947 | 46.72 | 800.6 | 210357 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 44236 | 422485 | 9.55 | 650.3 | 422290 |
| SELECT websearch_to_tsquery($2, $1)::text | 54503 | 82 | 0.00 | 0.8 | 54503 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 511 | 16 | 0.03 | 1.5 | 0 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 48 | 14 | 0.29 | 0.6 | 48 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 511 | 4 | 0.01 | 0.0 | 511 |
| SELECT to_regclass($1) IS NOT NULL | 264 | 3 | 0.01 | 0.6 | 264 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 511 | 2 | 0.00 | 0.0 | 0 |
| SELECT coalesce(extract($2 FROM now() - ( SELECT created_at FROM outbox.outbox_event WHERE published_at IS NUL | 66 | 2 | 0.03 | 0.1 | 66 |
| SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL AND attempts >= $1 | 66 | 1 | 0.02 | 0.2 | 66 |
