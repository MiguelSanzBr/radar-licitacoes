# Prova: bloat de `ingest.procurement` e `outbox.outbox_event`

Data: 2026-10-23. Código da medição: `core/src/test/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/jdbc/DbBloatBenchTest.java`, no commit `64a6aa3` (squash do PR), com a V14 entregue (lock timeout e índice parcial dos pendentes).

## Ambiente

- Hardware: AMD Ryzen 5 5600GT (12 threads), 11 GiB de RAM, NVMe de 954 GB, Linux 6.12, Docker 29.9 com overlayfs. O host rodava outras compilações em paralelo (load average de 3 a 6): os tempos têm ruído, os tamanhos não.
- Postgres 18.6 (`postgres:18` via Testcontainers, contêiner próprio, nunca o compartilhado), `shared_buffers` 128 MB, `autovacuum_naptime` 5 s (o padrão é 60 s, ver a seção do autovacuum), demais parâmetros no padrão. Schema pelo Flyway (V1 a V14) e `CREATE EXTENSION pgstattuple`.
- Comando: `./mvnw -Pbench test -pl core -am -Dtest=DbBloatBenchTest -Dsurefire.failIfNoSpecifiedTests=false` (relatório em `core/target/db-bloat-bench.md`). Três execuções completas; os tamanhos saíram idênticos nas três.
- Nas medições de bloat o autovacuum fica desligado na tabela em teste (`autovacuum_enabled = false`), para os números mostrarem o que as atualizações deixam e não uma corrida com o daemon. A seção do autovacuum o deixa ligado.
- `pg_repack`: não está na imagem `postgres:18` (`pg_available_extensions` não o lista). Não foi medido; só `VACUUM` e `VACUUM FULL`.

## `ingest.procurement` (partição `procurement_y2026m10`)

Carga de 100 000 linhas sem os centavos (o estado de antes do expand), depois 100 000 `UPDATE`s produzidos pelo backfill real (lotes de 5 000), depois `VACUUM` e `VACUUM FULL`. Cada linha tem uns 1,1 KB (objeto de 256 caracteres e `raw` jsonb de uns 700).

| Passo | `table_len` (MiB) | `dead_tuple_percent` | `free_percent` | índices (MiB) |
|---|---|---|---|---|
| após carregar 100 000 linhas | 111,6 | 0,00 | 1,68 | 9,0 |
| após 100 000 `UPDATE`s (backfill) | 223,2 | 0,01 | 50,17 | 14,3 |
| após `VACUUM` | 223,2 | 0,00 | 50,32 | 14,3 |
| após `VACUUM FULL` | 111,6 | 0,00 | 1,00 | 5,3 |

Os índices incluem o índice parcial dos pendentes (V14): as 100 000 linhas sem centavos entram nele, o que explica os 9,0 MiB iniciais contra 5,1 sem ele; depois do backfill ele fica vazio e o `VACUUM FULL` volta aos 5,3 MiB da chave primária.

Leitura: o backfill dobra o arquivo (111,6 para 223,2 MiB) e deixa versões mortas nos índices. O `dead_tuple_percent` quase não aparece porque os lotes do backfill leem as páginas antigas e o Postgres poda as versões mortas na hora (poda oportunista de página); o espaço liberado fica no arquivo, e é o `free_percent` de 50% que mostra o bloat. O `VACUUM` comum só zera o morto, não devolve espaço ao sistema (`table_len` igual); só o `VACUUM FULL` volta a 111,6 MiB. Nenhuma das 100 000 atualizações foi HOT: as páginas estão cheias (fillfactor 100, sem espaço para a nova versão na mesma página) e a coluna atualizada entra no predicado do índice dos pendentes.

Conclusão para a tabela de licitações: um backfill de uma coluna inteira custa o dobro do espaço da partição até a próxima compactação. Duas coisas seguram o custo: só as partições que têm linhas sem centavos dobram (as linhas gravadas depois do expand já nascem com eles), e o `VACUUM` comum depois do backfill deixa o espaço reutilizável pelas próximas inserções do mesmo mês. Quando o espaço for necessário de volta, `VACUUM FULL` na partição (trava exclusiva da partição durante a reescrita) ou `DETACH` e recarga; `pg_repack` ficaria como alternativa sem trava, mas não foi medido.

## `outbox.outbox_event`

Carga de 100 000 eventos pendentes (payload de uns 900 bytes), depois 100 000 `UPDATE`s no formato do relay (`UPDATE ... SET published_at = now() WHERE id BETWEEN ? AND ?`, lotes de 100, uma transação por lote), `VACUUM`, `VACUUM FULL`.

| Passo | `table_len` (MiB) | `dead_tuple_percent` | `free_percent` | índices (MiB) |
|---|---|---|---|---|
| após carregar 100 000 eventos pendentes | 111,6 | 0,00 | 8,48 | 8,6 |
| após 100 000 `UPDATE`s (relay marca publicado) | 221,3 | 44,21 | 8,72 | 12,1 |
| após `VACUUM` | 221,3 | 0,00 | 53,32 | 12,1 |
| após `VACUUM FULL` | 111,6 | 0,00 | 7,80 | 5,2 |

Leitura: marcar o evento como publicado reescreve a linha inteira (nenhum `UPDATE` foi HOT: `published_at` entra no predicado do índice parcial `outbox_pending_idx`). Sem `VACUUM`, a tabela dobra e 44% das tuplas ficam mortas. O `VACUUM` zera o morto e deixa 53% do arquivo livre para os próximos eventos; só o `VACUUM FULL` o devolve.

### O autovacuum do outbox durante uma execução do relay

A migration V4 já fixa `autovacuum_vacuum_scale_factor = 0.01` e `autovacuum_analyze_scale_factor = 0.005` na tabela (padrões do banco: 0,2 e 0,1). Para medir o efeito, uma tabela de controle com a mesma estrutura e os padrões do banco recebeu as mesmas 100 000 linhas, e as duas foram atualizadas juntas no formato do relay (lotes de 100, uma pausa de 25 ms por lote, 100 000 linhas em 29 a 30 s), com o autovacuum ligado e `n_dead_tup`, `autovacuum_count` e `last_autovacuum` lidos de `pg_stat_user_tables` a cada 500 ms. `autovacuum_naptime` ficou em 5 s para a execução caber em meio minuto; em produção, com 60 s, cada vacuum começa até 55 s depois do limite, e os picos tendem a ser maiores nas duas tabelas.

| Tabela | Pico de `n_dead_tup` (exec. 1 / 2 / 3) | Autovacuums na execução | Primeiro autovacuum (s) | Tamanho 20 s depois do fim, sem `VACUUM` manual (MiB) | `dead_tuple_percent` nesse momento |
|---|---|---|---|---|---|
| `outbox_event` (0,01 / 0,005) | 17 787 / 18 300 / 17 396 | 10 / 11 / 8 | 2 / 5 / 3 | 131,2 / 134,4 / 131,9 | 0,00 / 0,00 / 0,00 |
| controle (0,2 / 0,1) | 35 290 / 38 200 / 36 690 | 3 / 4 / 3 | 8 / 35 / 15 | 151,7 / 153,1 / 157,5 | 7,38 / 0,00 / 6,25 |

As execuções do relay duraram 47, 61 e 38 s (a pausa de 25 ms por lote mais o host disputado). `last_autovacuum` ao fim da execução do relay (exec. 1): `outbox_event` 03:48:16,1 e controle 03:48:10,9, com 4 600 e 10 844 tuplas mortas a essa hora (os valores oscilam, o daemon vive limpando).

Leitura: com o fator de 0,01 o autovacuum disparou de 8 a 11 vezes contra 3 ou 4, começou antes, segurou o pico de tuplas mortas na metade e terminou com o arquivo 18% a 20% acima do tamanho carregado (131 a 134 MiB contra 111,6), enquanto a tabela com o padrão terminou de 36% a 41% acima (152 a 158 MiB), na maioria das vezes ainda com tuplas mortas (a execução 2 a pegou logo depois de um vacuum). Sem autovacuum nenhum a tabela chegou a 221 MiB (tabela acima). O ajuste do outbox faz o que a V4 promete, mas não elimina o bloat: o arquivo não encolhe sozinho, só para de crescer.

## Conclusão

1. Uma coluna reescrita em toda a tabela dobra o arquivo até o `VACUUM FULL` (ingest) e o outbox faz o mesmo a cada ciclo de publicar: o `VACUUM` comum não devolve disco, só reaproveitamento.
2. O ajuste de autovacuum do outbox (V4) é o que mantém a tabela perto do tamanho real durante o relay. Os padrões do banco deixariam o dobro de tuplas mortas no pico (de 35 mil a 38 mil contra 17 mil a 18 mil).
3. O cleanup do outbox apaga só os publicados com mais de 7 dias (`OutboxCleanup`); o espaço dessas linhas volta pelo `VACUUM`, não por `VACUUM FULL`, e só se o arquivo precisar encolher é que `VACUUM FULL` na janela de manutenção ou `pg_repack` se justificam.

## Limites

- Uma partição de 100 000 linhas e uma tabela de 100 000 eventos: a tabela de produção terá milhões e a proporção (arquivo dobrado) é o que se transfere, não os segundos.
- Autovacuum desligado nas tabelas de teste durante as medições de bloat, e `autovacuum_naptime` de 5 s na comparação do outbox: ambos descritos acima.
- `pg_repack` não medido.
