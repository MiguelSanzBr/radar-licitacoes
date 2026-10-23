# pg_stat_statements: as 10 consultas que mais gastaram tempo

Zerado antes do S1 e lido depois do S6; os EXPLAIN rodam antes do reset e não entram. Tempos em ms.

| Consulta | Chamadas | Total (ms) | Média (ms) | Máximo (ms) | Linhas |
| --- | ---: | ---: | ---: | ---: | ---: |
| SELECT page.id, page.object_description, page.organ_name, page.unit_name, page.uf, page.municipality_name, pag | 4296 | 1731897 | 403.14 | 15315.9 | 90216 |
| SELECT organ_name, word_similarity($1, organ_name) AS s FROM search.procurement_search_view WHERE $2 <% organ_ | 190 | 209261 | 1101.37 | 2992.9 | 1681 |
| SELECT page.id, page.object_description, page.organ_name, page.unit_name, page.uf, page.municipality_name, pag | 11769 | 133020 | 11.30 | 927.6 | 113211 |
| select pe1_0.numero_controle_pncp,pe1_0.published_at,pe1_0.awarded_value,pe1_0.closed_emitted_at,pe1_0.content | 105 | 22726 | 216.44 | 14797.5 | 105 |
| SELECT numero_controle_pncp AS id, left(object_description, $3) AS object_description, organ_name, unit_name,  | 1202 | 13919 | 11.58 | 95.1 | 24040 |
| SELECT detail::text, etag FROM search.procurement_detail_view WHERE numero_controle_pncp = $1 | 4413 | 11158 | 2.53 | 282.9 | 4413 |
| select pe1_0.numero_controle_pncp,pe1_0.published_at,pe1_0.awarded_value,pe1_0.closed_emitted_at,pe1_0.content | 4 | 4987 | 1246.70 | 4918.3 | 105 |
| COPY (SELECT replace(numero_controle_pncp, '/', '-') FROM search.procurement_detail_view ORDER BY random() LIM | 1 | 2372 | 2372.15 | 2372.2 | 20000 |
| SELECT source_version FROM search.procurement_detail_view WHERE numero_controle_pncp = $1 FOR UPDATE | 105 | 2345 | 22.34 | 597.3 | 105 |
| UPDATE search.procurement_search_view SET status = coalesce($1::text, status), source_version = $2::integer, p | 105 | 2119 | 20.18 | 407.2 | 105 |
