# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 40 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 11431 | 468029 | 40.94 | 2559.2 | 240051 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 11825 | 80809 | 6.83 | 501.8 | 111938 |
| SELECT detail::text, etag FROM search.procurement_detail_view WHERE numero_controle_pncp = $1 | 4479 | 3075 | 0.69 | 62.7 | 4479 |
| COPY (SELECT replace(numero_controle_pncp, '/', '-') FROM search.procurement_detail_view ORDER BY random() LIM | 1 | 2484 | 2484.29 | 2484.3 | 20000 |
| SELECT websearch_to_tsquery($2, $1)::text | 23382 | 57 | 0.00 | 0.1 | 23382 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 142 | 44 | 0.31 | 2.2 | 142 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 1497 | 25 | 0.02 | 0.2 | 0 |
| SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, a.attnotnull OR (t.typtype = $1 AND t.typnotnull), a. | 40 | 22 | 0.54 | 1.3 | 40 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 1497 | 8 | 0.01 | 0.0 | 1497 |
| insert into ingest.ingestion_run (cursor_page,discovered,error,fetched,finished_at,kind,modality_id,started_at | 2 | 5 | 2.75 | 5.2 | 2 |
