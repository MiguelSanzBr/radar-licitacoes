# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 20 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 11503 | 392276 | 34.10 | 686.5 | 241563 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 11823 | 77063 | 6.52 | 625.7 | 113864 |
| SELECT detail::text, etag FROM search.procurement_detail_view WHERE numero_controle_pncp = $1 | 4411 | 2157 | 0.49 | 37.6 | 4411 |
| COPY (SELECT replace(numero_controle_pncp, '/', '-') FROM search.procurement_detail_view ORDER BY random() LIM | 1 | 2021 | 2021.07 | 2021.1 | 20000 |
| SELECT websearch_to_tsquery($2, $1)::text | 23459 | 54 | 0.00 | 0.1 | 23459 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 146 | 33 | 0.23 | 1.1 | 146 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 1527 | 24 | 0.02 | 0.3 | 0 |
| SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, a.attnotnull OR (t.typtype = $1 AND t.typnotnull), a. | 20 | 20 | 0.99 | 2.1 | 20 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 1527 | 7 | 0.00 | 0.0 | 1527 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 1527 | 4 | 0.00 | 0.0 | 0 |
