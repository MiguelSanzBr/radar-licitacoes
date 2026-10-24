# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do último cenário, com o pool de busca em 10 conexões. Os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 11508 | 390988 | 33.98 | 1007.3 | 241668 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 11835 | 74588 | 6.30 | 442.5 | 112935 |
| COPY (SELECT replace(numero_controle_pncp, '/', '-') FROM search.procurement_detail_view ORDER BY random() LIM | 1 | 2720 | 2719.86 | 2719.9 | 20000 |
| SELECT detail::text, etag FROM search.procurement_detail_view WHERE numero_controle_pncp = $1 | 4496 | 2497 | 0.56 | 67.6 | 4496 |
| SELECT websearch_to_tsquery($2, $1)::text | 23464 | 54 | 0.00 | 0.0 | 23464 |
| SELECT source_version FROM search.procurement_detail_view WHERE numero_controle_pncp = $1 FOR UPDATE | 104 | 28 | 0.27 | 2.4 | 104 |
| SELECT count(*) $1 count(*) FILTER (WHERE state = $2) FROM pg_stat_activity WHERE datname = $3 AND usename = $ | 146 | 28 | 0.19 | 1.0 | 146 |
| UPDATE search.procurement_search_view SET status = coalesce($1::text, status), source_version = $2::integer, p | 104 | 25 | 0.24 | 1.4 | 104 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 1520 | 24 | 0.02 | 0.4 | 105 |
| UPDATE search.procurement_detail_view AS pdv SET detail = patched.detail, etag = encode(sha256(convert_to(patc | 104 | 15 | 0.14 | 0.5 | 104 |
