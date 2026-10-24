# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 8 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 11556 | 927648 | 80.27 | 600.5 | 242676 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 46984 | 809288 | 17.22 | 928.8 | 452504 |
| SELECT websearch_to_tsquery($2, $1)::text | 58833 | 179 | 0.00 | 4.3 | 58833 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 45 | 23 | 0.50 | 3.9 | 45 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 506 | 20 | 0.04 | 0.4 | 0 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 506 | 5 | 0.01 | 0.1 | 506 |
| SELECT to_regclass($1) IS NOT NULL | 248 | 5 | 0.02 | 2.1 | 248 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 506 | 3 | 0.01 | 0.3 | 0 |
| SELECT coalesce(extract($2 FROM now() - ( SELECT created_at FROM outbox.outbox_event WHERE published_at IS NUL | 62 | 2 | 0.04 | 0.2 | 62 |
| SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL | 62 | 2 | 0.02 | 0.3 | 62 |
