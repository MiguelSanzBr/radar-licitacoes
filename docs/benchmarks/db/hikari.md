# Prova: tamanho do pool do Hikari por medida

Data: 2026-10-23. Commit: `e19a55d`. Código: `scripts/bench.sh` no modo `make bench-pool` (variável `BENCH_POOL_SIZES`) e os cenários Gatling S1, S2 e S5 de `perf/`. Dados brutos em `docs/benchmarks/db/hikari/`.

**Dados sintéticos.** A massa é a mesma dos benchmarks de busca (`docs/benchmarks/search/`): 1.200.000 linhas na visão de busca, das quais 1.633 reais (PNCP, 2026-10-21) e 1.198.367 sintéticas (`tools/synth`, ADR-0005). Os números valem para esta massa e esta máquina, não para produção.

## Ambiente

- Hardware: AMD Ryzen 5 5600GT (6 núcleos, 12 threads), 11,6 GiB de RAM, NVMe, Linux 6.12, Docker 29.9. Aplicação, Postgres, Gatling e o resto da pilha do compose rodam na mesma máquina, então disputam as mesmas 12 threads. Ficha completa em [`hikari/env.md`](hikari/env.md).
- Postgres 18.6 com 2 GiB de memória (`docker-compose.bench.yml`), `shared_buffers=512MB`, `max_connections=100`, `work_mem=4MB`. Aplicação com 640 MiB.
- A massa foi carregada uma vez e reaproveitada nos três tamanhos. Entre dois tamanhos só a aplicação reinicia e o cache do detalhe (Redis) é esvaziado, para o S5 não herdar acertos do tamanho anterior. O Postgres e seu cache de páginas ficam como estão: o tamanho 10 roda primeiro e o 20 e o 40 partem de cache igual ou mais quente. A diferença entre os três é menor que a variação de uma execução e não segue essa ordem, então não pesa na conclusão.
- O pool medido é o da busca (`searchDataSource`, nome `search` nas métricas): é ele que atende S1, S2 e S5 (`searchReadDsl`) e o projetor. O pool de escrita (JPA, coleta, outbox) ficou em 10 durante tudo.
- Variável da aplicação: `RADAR_SEARCH_POOL_SIZE` (`radar.search.datasource.maximum-pool-size`). O script confere `hikaricp_connections_max{pool="search"}` depois de cada reinício e aborta se o valor não for o pedido.

## Como repetir

```
make image
CLEAN=1 make down
make bench-pool                 # 10, 20 e 40; uns 60 minutos, 6 GB de RAM livres (BENCH_MIN_FREE_MB)
```

Uma execução por tamanho, 60 s de aquecimento e 180 s medidos, os mesmos usuários e a mesma pausa de 1 s dos benchmarks de busca (S1 50, S2 50, S5 100). Sem repetição e sem desvio padrão: diferenças de poucos por cento entre linhas não são conclusão.

As métricas do Hikari vêm de `/actuator/prometheus` (a cada 5 s, só a fase medida) e as conexões do Postgres de `pg_stat_activity` (papel `radar_read_writer`).

## Carga do benchmark de busca (pausa de 1 s)

Os usuários do plano esperam 1 s entre requisições. Resultado, por tamanho do pool de busca:

| Cenário | Pool | Requisições | Erros | req/s | p50 (ms) | p95 (ms) | p99 (ms) | Ativas (média / máx.) | Pendentes (máx.) | Uso por conexão (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 busca textual, 50 usuários | 10 | 8.687 | 0 | 35,6 | 32 | 74 | 111 | 1,9 / 5 | 0 | 16,6 |
| | 20 | 8.692 | 0 | 35,8 | 31 | 69 | 89 | 1,8 / 5 | 0 | 16,3 |
| | 40 | 8.672 | 0 | 35,5 | 33 | 76 | 107 | 1,4 / 5 | 0 | 17,6 |
| S2 busca filtrada, 50 usuários | 10 | 8.930 | 0 | 36,9 | 6 | 21 | 44 | 0,5 / 3 | 0 | 3,1 |
| | 20 | 8.928 | 0 | 36,9 | 6 | 20 | 43 | 0,2 / 2 | 0 | 3,0 |
| | 40 | 8.921 | 0 | 36,9 | 6 | 22 | 46 | 0,2 / 1 | 0 | 3,2 |
| S5 detalhe, 100 usuários | 10 | 17.976 | 0 | 74,3 | 1 | 2 | 4 | 0,0 / 0 | 0 | 0,3 |
| | 20 | 17.975 | 0 | 74,3 | 1 | 2 | 4 | 0,0 / 0 | 0 | 0,3 |
| | 40 | 17.972 | 0 | 74,3 | 1 | 3 | 8 | 0,0 / 0 | 0 | 0,3 |

- Nenhuma diferença entre 10, 20 e 40: o pico de conexões ativas foi 5 (S1), 3 (S2) e 0 (S5, quase tudo sai do cache do detalhe: 81% de acertos nos três tamanhos), e a fila (`hikaricp_connections_pending`) ficou em 0 sempre. Com pausa de 1 s e consultas de 17 ms (S1), 50 usuários mantêm menos de 2 conexões em uso em média.
- A espera por conexão (`hikaricp_connections_acquire_seconds`) média foi 0,01 ms.
- Isso diz que 10 conexões sobram para a carga prevista, mas não onde o pool começa a pesar: para achar o joelho é preciso uma carga que mantenha mais consultas em voo que conexões. Daí a rodada seguinte.

## Saturação (sem pausa)

Complemento fora do plano: S1 e S2 com os mesmos 50 usuários, mas **sem pausa** (`BENCH_THINK_MS=0`): cada usuário dispara a próxima requisição assim que recebe a resposta, então há sempre 50 consultas querendo conexão. 30 s de aquecimento e 90 s medidos por cenário, tamanhos 4, 8, 10, 20 e 40, mesma massa e mesmo procedimento (comando abaixo). Em circuito fechado a latência é, na prática, usuários dividido pela vazão: o que interessa é a vazão e a cauda. Dados em [`hikari/saturation/`](hikari/saturation/).

```
BENCH_REUSE=1 BENCH_KEEP=1 BENCH_THINK_MS=0 BENCH_WARMUP_S=30 BENCH_MEASURED_S=90 BENCH_SCENARIOS="s1 s2" \
  BENCH_POOL_SIZES="4 8 10 20 40" BENCH_POOL_DIR=docs/benchmarks/db/hikari/saturation scripts/bench.sh
```

| Cenário | Pool | Requisições | Erros | req/s | p50 (ms) | p95 (ms) | p99 (ms) | Ativas (média) | Pendentes (média / máx.) | Uso por conexão (ms) | Espera por conexão (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 | 4 | 7.782 | 0 | 63,8 | 575 | 745 | 930 | 4,0 | 43,6 / 46 | 23,0 | 265 |
| | 8 | 9.031 | 0 | 74,0 | 484 | 805 | 1.005 | 8,0 | 41,4 / 42 | 39,9 | 208 |
| | **10** | 8.943 | 0 | 73,3 | 489 | 816 | 1.009 | 10,0 | 37,6 / 40 | 50,2 | 199 |
| | 20 | 8.369 | 0 | 68,6 | 509 | 906 | 1.112 | 19,9 | 29,5 / 30 | 107,7 | 158 |
| | 40 | 7.993 | 0 | 65,5 | 466 | 1.234 | 1.474 | 40,0 | 9,5 / 10 | 224,0 | 52 |
| S2 | 4 | 33.857 | 0 | 277,5 | 129 | 190 | 232 | 3,6 | 40,5 / 46 | 5,0 | 60 |
| | 8 | 36.048 | 0 | 295,5 | 117 | 221 | 283 | 7,9 | 40,8 / 42 | 9,5 | 50 |
| | **10** | 35.589 | 0 | 291,7 | 116 | 232 | 307 | 9,8 | 38,4 / 40 | 12,1 | 48 |
| | 20 | 33.280 | 0 | 272,8 | 119 | 265 | 387 | 20,0 | 28,1 / 30 | 26,5 | 37 |
| | 40 | 32.517 | 0 | 266,5 | 102 | 364 | 685 | 37,0 | 6,7 / 10 | 54,6 | 11 |

Leitura:

- **A vazão sobe até 8 a 10 conexões e cai depois.** S1: 63,8, 74,0, 73,3, 68,6 e 65,5 req/s; S2: 277, 296, 292, 273 e 267. De 10 para 40 o S1 perde 11% de vazão e o p95 sobe de 816 para 1.234 ms; o S2 perde 9% e o p95 vai de 232 para 364 ms e o p99 de 307 para 685 ms.
- **Por que cai.** Com o pool cheio, quem espera a conexão é a fila do Hikari (pendentes, coluna "Espera"); com o pool grande, a espera some, mas o tempo que cada consulta segura a conexão cresce quase na proporção do pool (S1: 23, 40, 50, 108 e 224 ms para 4, 8, 10, 20 e 40). A mesma carga de CPU do Postgres passou a ser dividida entre mais consultas ao mesmo tempo, e o custo de trocar de contexto e de disputar páginas aparece na cauda. Essa explicação (CPU compartilhada) é a mais coerente com os números, mas não foi isolada por medida: não há perfil de CPU por processo nesta rodada.
- Sem erros em nenhuma linha, nem com 40 consultas pesadas em voo: o `statement_timeout` de 3 s não foi atingido.
- Pool de 4 perde 10 req/s no S1 (14% de vazão) mas é o de menor p95 do S1 e do S2; o p50 maior (575 ms) é a fila. É o sinal de que abaixo de 8 o pool vira gargalo.

## Fórmula de referência

O guia do HikariCP (herdado da regra do PostgreSQL) sugere `conexões = núcleos × 2 + discos`. Para esta máquina: 6 núcleos físicos × 2 + 1 (um NVMe) = **13**; contando as 12 threads como núcleos, 25.

- O joelho medido ficou em **8 a 10**, abaixo do 13 da fórmula com núcleos físicos e bem abaixo do 25. Aplicação, Gatling e as consultas disputam as mesmas 12 threads, então o Postgres só tem parte delas: com um host de banco dedicado a conta deve subir para perto da fórmula. Isso não foi medido aqui.
- Seguir a fórmula (13) ficaria um pouco acima do joelho: entre 8 e 20 o S1 vai de 74,0 para 68,6 req/s e o p95 piora 12% (805 para 906 ms). Aqui a fórmula é um teto razoável, não o ponto ótimo.

## Escolha

**Pool de busca: 10 conexões** (`RADAR_SEARCH_POOL_SIZE=10`, o padrão do repositório).

- É o joelho da curva: 8 e 10 empatam em vazão e em cauda (S1 74,0 e 73,3 req/s, p95 805 e 816 ms; S2 296 e 292 req/s), e passar disso só piora o p95 e o p99 sem ganhar vazão.
- Na carga do plano, 10 dá folga grande: pico de 5 conexões ativas, fila 0.
- **Orçamento do Postgres.** `max_connections` é 100 (3 reservadas ao superusuário). Cada instância da aplicação abre até 10 do pool de escrita mais 10 do pool de busca: 20 por instância, então cabem 4 instâncias (80) com folga para o Debezium (perfil `cdc`) e consultas administrativas. Com 40 no pool de busca seriam 50 por instância: 1 instância e meia, com a vazão pior.
- **Orçamento de memória.** Cada conexão é um processo do Postgres, com `work_mem` (4 MB) por nó de ordenação ou hash da consulta. O consumo por conexão não foi medido; durante as rodadas o banco ficou dentro dos 2 GiB do contêiner nos cinco tamanhos, sem reinício.
- Se a carga real passar o plano (mais usuários, resposta mais lenta do banco), o sinal para subir o pool é `hikaricp_connections_pending` acima de 0 de forma sustentada com o Postgres ainda com CPU livre; subir sem CPU livre só alonga a cauda, como acima.

`pg_stat_statements` das 10 consultas que mais gastaram tempo na carga do plano com o pool de busca em 10: [`hikari/pool-10/pg_stat_statements.md`](hikari/pool-10/pg_stat_statements.md). Duas linhas são do benchmark, não da aplicação: o `COPY` que sorteia os ids do S5 e a leitura de `pg_stat_activity` do amostrador. As duas consultas de busca (S1 e S2) são 99% do tempo total.

## Pool de escrita (JPA, coleta, outbox, alertas)

**Não medido.** Fica em 10 (`RADAR_DB_POOL_SIZE`, `spring.datasource.hikari.maximum-pool-size`), o mesmo valor que o Hikari já usava por padrão, agora explícito e configurável. O raciocínio vem da carga, não de medida:

- Quem usa esse pool: as tarefas agendadas (o agendador tem 4 threads, então no máximo 4 delas ao mesmo tempo: relay do outbox, coleta incremental, fechamento, backfill), os consumidores de alertas (assinantes, buscas salvas, entregas, idempotência), o painel de operação (DLQ e estatísticas do pipeline) e a gravação do outbox junto com a transação de coleta. As threads são virtuais no Tomcat, então nada limita as requisições além do próprio pool, mas as que tocam este pool são poucas e curtas. São de 6 a 10 conexões simultâneas no pior caso; na prática o relay do outbox (0,02 ms de média por consulta no `pg_stat_statements`) e a coleta incremental (limitada a 0,2 req/s pelo PNCP, ADR-0017) seguram a conexão por pouco tempo.
- Cada ciclo de coleta usa uma transação por vez, não um lote de conexões: aumentar o pool não aceleraria a coleta, que é limitada pela taxa do PNCP.
- O projetor roda no pool de busca (grava com `searchDsl`), então não disputa com a escrita.
- Não há medida barata: o replay (`ProjectionReplayIT`) usa o pool de busca, não este. Se algum dia o pool de escrita aparecer saturado, o sinal é o mesmo `hikaricp_connections_pending` (com `pool="HikariPool-1"`).
