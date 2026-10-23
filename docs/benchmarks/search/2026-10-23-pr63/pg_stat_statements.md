# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do S4 (rodada com o teto de candidatos e o plano por valor, ver README); os EXPLAIN rodam depois e não entram. S5 não foi refeito nesta rodada. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $7) AS object_description, v.organ_name, v.uni | 11454 | 424223 | 37.04 | 910.0 | 240534 |
| SELECT v.numero_controle_pncp AS id, left(v.object_description, $9) AS object_description, v.organ_name, v.uni | 11814 | 75788 | 6.42 | 468.5 | 114272 |
| SELECT organ_name, word_similarity($1, organ_name) AS s FROM search.organ WHERE $2 <% organ_name ORDER BY s DE | 4725 | 54731 | 11.58 | 23.0 | 45811 |
| SELECT set_config($2, $1, $3) | 4725 | 74 | 0.02 | 0.1 | 4725 |
| SELECT websearch_to_tsquery($2, $1)::text | 23409 | 58 | 0.00 | 1.6 | 23409 |
| SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version, payload::text, headers::text, c | 1517 | 27 | 0.02 | 0.2 | 0 |
| SELECT pg_try_advisory_xact_lock(hashtextextended($1, $2)) | 1517 | 8 | 0.00 | 0.0 | 1517 |
| BEGIN | 4725 | 6 | 0.00 | 0.0 | 0 |
| UPDATE outbox.outbox_event SET published_at = now() WHERE id = ANY ($1) | 1517 | 4 | 0.00 | 0.0 | 0 |
| select pe1_0.numero_controle_pncp,pe1_0.published_at,pe1_0.awarded_value,pe1_0.closed_emitted_at,pe1_0.content | 1 | 3 | 3.12 | 3.1 | 0 |
