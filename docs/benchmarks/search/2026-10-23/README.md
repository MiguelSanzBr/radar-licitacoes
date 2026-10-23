# Benchmark da busca com 1,2 milhão de linhas (2026-10-23)

Medido por `make bench` (commit `c82905b`). **Inclui dados sintéticos (1.198.367 linhas).** Das 1.200.000 linhas da visão de busca, 1.633 são reais (publicações de 2026-10-21 da modalidade 6, coletadas em 2026-10-22 e versionadas em `infra/compose/seed`) e 1.198.367 saem de `tools/synth` (`source='synthetic'`, ids `SYN-`, texto por n-gramas do corpus real, ADR-0005). Isso vale para toda tabela e número deste documento. Os números vêm de uma execução por cenário, na máquina descrita abaixo, que ficou compartilhada com o ambiente gráfico do desenvolvedor durante a medição.

Arquivos desta pasta: `env.md` (hardware, Postgres, limites), `explain/` (planos antes e depois dos índices), `gatling/` (estatísticas por requisição), `pg_stat_statements.md`, `run.json` e `synth.log`.

## Resumo

- Cumprem o alvo do plano (ADR-0025): **S2** (busca com UF, modalidade e `open=true`: p95 de 44 ms e p99 de 204 ms) e **S5** (detalhe: p95 de 2 ms com 81,6% de acerto no cache).
- **Não cumprem:** **S1** (busca textual: p95 de 3.402 ms e p99 de 5.857 ms, 142 respostas de erro em 3.943) e **S4** (órgão com erro de digitação: 544 erros em 720 requisições). O alvo não foi mexido; a ADR-0025 registra o valor medido.
- O keyset custa o mesmo em qualquer profundidade (p95 de 3 ms nas páginas 1, 100 e 1.000); o `OFFSET` equivalente vai de 4 ms a 38 ms.
- Os índices valem muito para o que filtra ou ordena por chave (a página 1 do keyset cai de 938 ms para 0,1 ms, a busca com termo raro de 857 ms para 6,6 ms) e não resolvem os dois casos ruins: termo comum no S1 e trigrama no S4.

## Como rodar de novo

```
CLEAN=1 make down   # o benchmark parte de um banco vazio
make image          # a imagem precisa conter o endpoint de OFFSET desligado por padrão
make bench          # cerca de uma hora; BENCH_* em scripts/bench.sh
```

`make bench` grava tudo nesta pasta, escreve `docs/benchmarks/latest.json` e reescreve o bloco de números do `README.md` da raiz. `make docs-check` confere que o bloco continua igual ao que o `latest.json` gera.

## Ambiente

Detalhes em `env.md`. Em resumo: AMD Ryzen 5 5600GT (6 núcleos, 12 threads), 11,6 GiB de RAM, NVMe, Linux 6.12, Docker 29.9, Postgres 18.6 em contêiner, aplicação e demais serviços do compose no mesmo host, cliente de carga (Gatling) também.

Ajustes ao compose só para o benchmark (`infra/compose/docker-compose.bench.yml`):

| Ajuste | Motivo |
| --- | --- |
| Postgres com 2 GiB (o compose de desenvolvimento dá 256 MiB), `shared_buffers=512MB`, `effective_cache_size=1536MB`, `maintenance_work_mem=256MB` | 1,2 milhão de linhas com 3 GiB de dados e índices não cabem em 256 MiB: sem memória, o benchmark mediria o disco |
| `RADAR_RATE_LIMIT_ENABLED=false` | 50 usuários de um IP estourariam o balde anônimo de 30 req/min (ADR-0027) e a medição veria 429, não a busca |
| `RADAR_BENCH_OFFSET_ENDPOINT=true` | a rota `/bench/offset` do S3 não existe sem esta chave |

Todo o resto é o do compose: aplicação com 640 MiB, `statement_timeout` de 3 s na leitura, Redis, Kafka, Keycloak e o `otel-lgtm` ligados.

### O que foi adaptado à máquina

- **RAM livre.** O script pede 6 GB livres antes de começar. A máquina tinha cerca de 5 GB disponíveis (navegador e outros aplicativos do desenvolvedor abertos, e cerca de 5 GB de swap já em uso antes do início), e esse valor não subiu em 10 min de espera. A medição rodou com `BENCH_MIN_FREE_MB=4500`; o vigia que aborta com menos de 1 GB livres ficou ligado e não disparou. Os números carregam esse ruído do ambiente.
- **Uma execução por cenário, e não três.** O plano pede 3 execuções e a mediana dos p95; foi uma só, 1 min de aquecimento descartado e 3 min medidos. Não há desvio entre execuções.
- **Carga.** Usuários do plano (S1 50, S2 50, S3 10, S4 20, S5 100, S6 10), modelo fechado, 1 s de pausa entre requisições. A carga usada foi a planejada em todos os cenários: o vigia de memória não disparou e o Gatling não ficou para trás (modelo fechado com pausa), mas não há medida de CPU do cliente de carga durante a execução. Onde houve erro, a causa é a consulta que passa dos 3 s do `statement_timeout` (os cortes aparecem como p50 de 3.007 ms na rodada reduzida do S4). Para separar custo da consulta de saturação, S1 e S4 rodaram de novo com menos usuários (abaixo).
- **EXPLAIN sem índices.** Em vez da flag Flyway `bench.noindex` do plano, `scripts/explain/run.sh before` apaga os índices secundários dentro de uma transação e termina em `ROLLBACK` (o DDL do Postgres é transacional). A chave primária fica. Cada plano roda duas vezes e vale a segunda (a primeira aquece o cache de páginas).
- **Estatísticas.** `tools/synth` não passa pelo projetor, então `procurement_stats_view` foi reconstruída da visão de busca com o mesmo `GROUP BY` da migração V15 antes da medição. Tem 1.692 baldes.
- **Gatling 3.15.1 não grava `stats.json`.** `scripts/gatling-stats.sh` lê a tabela de estatísticas do `index.html` do relatório.
- **S7 e S8 não foram refeitos aqui.** Ingestão e replay estão em `docs/benchmarks/replay/2026-10-22.md` (replay de 50 mil eventos) e `docs/benchmarks/chaos/2026-10-23.md` (caos). O S7 do plano (WireMock servindo 100 mil registros) fica pendente.

## Carga: tempo e tamanho

`tools/synth` carregou 1.198.367 linhas em 457,5 s (2.619 linhas/s) com os índices já criados. A visão de busca tem 1.098 MB de tabela e 671 MB de índices; o detalhe, 1.969 MB.

## Resultados por cenário

Fase medida de 3 min, sem o aquecimento. Taxa = requisições da fase medida divididas por 180 s.

| Cenário | Usuários | Requisições | Erros | p50 (ms) | p95 (ms) | p99 (ms) | req/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 busca textual | 50 | 3.943 | 142 | 1.005 | 3.402 | 5.857 | 21,9 |
| S2 busca com UF, modalidade e `open=true` | 50 | 8.879 | 0 | 8 | 44 | 204 | 49,3 |
| S3 keyset página 1 | 10 | 300 | 0 | 2 | 3 | 5 | 1,7 |
| S3 OFFSET página 1 | 10 | 300 | 0 | 2 | 4 | 6 | 1,7 |
| S3 keyset página 100 | 10 | 300 | 0 | 2 | 3 | 6 | 1,7 |
| S3 OFFSET página 100 | 10 | 300 | 0 | 5 | 7 | 8 | 1,7 |
| S3 keyset página 1.000 | 10 | 300 | 0 | 2 | 3 | 7 | 1,7 |
| S3 OFFSET página 1.000 | 10 | 300 | 0 | 31 | 38 | 48 | 1,7 |
| S4 órgão com erro de digitação | 20 | 720 | 544 | 4.393 | 5.025 | 5.526 | 4,0 |
| S5 detalhe | 100 | 17.955 | 0 | 1 | 2 | 57 | 99,8 |
| S6 estatísticas por UF | 10 | 900 | 0 | 2 | 2 | 3 | 5,0 |
| S6 estatísticas por mês | 10 | 900 | 0 | 2 | 2 | 3 | 5,0 |

Leitura:

- **S1.** A latência depende do termo. Um termo raro devolve em poucos milissegundos pelo GIN; um termo comum casa com um terço da tabela (o "contratação" casa com 403 mil linhas) e o `ts_rank_cd` precisa pontuar todas antes do `LIMIT`, porque o rank não é indexável (ADR-0011 já listava este caso como o ruim). Com 50 usuários simultâneos, as consultas pesadas disputam CPU e o p50 sobe para 1 s. É custo da consulta, não só de concorrência: com 10 usuários o S1 ainda deu p95 de 1.888 ms, p99 de 4.470 ms e 36 erros em 1.321 (`gatling/stepdown/s1-10users.stats.json`), mas p50 de 108 ms.
- **S2.** O `open=true` e a UF cortam o conjunto (o planejador usa `psv_open_closing`) e a busca cabe no alvo. O p99 de 204 ms é o que mais se aproxima do limite de 500 ms.
- **S3.** O `OFFSET` da página 1.000 lê 20.000 linhas pelo índice e as descarta, enquanto o keyset parte da chave. Nesta escala o `OFFSET` é lento mas não proibitivo (31 ms); o custo cresce com a profundidade, o do keyset não.
- **S4.** Cada consulta custa cerca de 2 s sozinha (EXPLAIN abaixo) e passa dos 3 s do `statement_timeout` com concorrência. Mesmo com 2 usuários, 79 de 103 respostas foram erro, com p50 de 3.007 ms (o corte). O texto de teste ("camraa municipal") casa com cerca de dois terços das linhas (807 mil de 1,2 milhão, porque `municipal` está em quase todo nome de órgão), e o planejador escolhe varredura sequencial em vez de `psv_organ_trgm`. O ADR-0011 previa meio segundo com um caso ruim de 20% de casamento; o número oficial é 2 s.
- **S5.** A taxa de acerto lida dos contadores da aplicação foi de 81,6% (19.531 acertos e 4.413 faltas, aquecimento incluído), com 80% das leituras sobre 200 ids quentes e o resto espalhado por 19.800 ids frios. O p99 de 57 ms vem das faltas sob 100 usuários; o SLO é p95. Não houve rodada sem cache, então este documento não mede o ganho do cache sobre o Postgres.
- **S6.** A tabela de estatísticas tem 1.692 linhas: a latência não depende do tamanho da visão de busca.

## EXPLAIN antes e depois dos índices

Planos completos em `explain/`. SQL tirado de `JooqSearchReadModel`, `JooqOrganReadModel`, `JooqDetailReadModel` e `JooqStatsReadModel`, com o texto da busca como literal (a aplicação o passa como valor).

| Consulta | Antes (ms) | Depois (ms) | Buffers antes | Buffers depois | Nó antes | Nó depois |
| --- | ---: | ---: | --- | --- | --- | --- |
| `s1-text-common` | 1774,4 | 602,8 | acertos=150189 leituras=117108 | acertos=161028 leituras=106269 | Parallel Seq Scan | Parallel Seq Scan |
| `s1-text-rare` | 857,3 | 6,6 | acertos=105726 leituras=115380 | acertos=949 | Parallel Seq Scan | Bitmap Heap Scan |
| `s2-text-filters` | 419,7 | 71,5 | acertos=104557 leituras=116637 | acertos=53078 | Parallel Seq Scan | Index Scan using psv_open_closing |
| `s3-keyset-first` | 938,3 | 0,115 | acertos=24403 leituras=111453 | acertos=12 | Parallel Seq Scan | Index Scan using psv_published_keyset |
| `s3-keyset-deep` | 792,5 | 0,104 | acertos=22613 leituras=113181 | acertos=9 | Parallel Seq Scan | Index Scan using psv_published_keyset |
| `s3-offset-deep` | 3300,3 | 37,7 | acertos=26131 leituras=109725 | acertos=5755 | Parallel Seq Scan | Index Scan using psv_published_keyset |
| `s4-organ-typo` | 2264,7 | 2038,7 | acertos=27784 leituras=107997 | acertos=31965 leituras=103816 | Parallel Seq Scan | Parallel Seq Scan |
| `s5-detail` | 0,061 | 0,110 | acertos=4 | acertos=4 | Index Scan using procurement_detail_view_pkey | Index Scan using procurement_detail_view_pkey |
| `s6-stats` | 0,838 | 0,553 | acertos=16 | acertos=16 | Seq Scan | Seq Scan |

(`leituras` são páginas pedidas ao sistema operacional, que as serve do cache de páginas do host quando já estão lá.)

- Termo raro, `open=true` e keyset ganham de 6 a 8.000 vezes com o índice: o GIN e os btrees fazem o que foi desenhado para fazer.
- O keyset da página 1.000 custa 0,1 ms e o `OFFSET` equivalente, 37,7 ms com índice e 3,3 s sem (ordenação externa em disco de cerca de 490 MB).
- O termo comum continua com varredura sequencial paralela com índice: o planejador acerta ao não usar o GIN para um termo que casa com um terço da tabela, e o gargalo passa a ser o ranking de 400 mil linhas (603 ms).
- `psv_organ_trgm` não é usado: o plano continua sequencial (2 s com e sem o índice).
- S5 e S6 não dependem de índice secundário: a chave primária e a tabela de 1.692 linhas bastam.

## pg_stat_statements: as 10 consultas que mais gastaram tempo

Tabela em `pg_stat_statements.md` (zerado antes do S1, lido depois do S6). As três primeiras:

1. A busca com texto (S1): 4.296 chamadas, média de 403 ms, máxima de 15,3 s; 1.731 s de tempo total, a maior parte do trabalho do Postgres no benchmark.
2. A busca de órgãos (S4): 190 chamadas, média de 1,1 s, máxima de 3,0 s (o `statement_timeout`).
3. A busca com filtros (S2): 11.769 chamadas, média de 11 ms.

Apareceram também 105 gravações do caminho de escrita da aplicação (Hibernate e projetor), com média de 216 ms na consulta mais lenta: provavelmente eventos do seed ainda sendo projetados quando o S1 começou, lentos porque o Postgres estava ocupado com a busca (causa não verificada).

## Rodada reduzida (S1 e S4)

Para separar custo da consulta de saturação do host, S1 e S4 rodaram de novo com menos usuários, sem mudar mais nada (1 min de aquecimento e 3 min medidos, mesmo banco). Estatísticas em `gatling/stepdown/`.

| Cenário | Usuários planejados | Usuários usados | Requisições | Erros | p50 (ms) | p95 (ms) | p99 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 | 50 | 10 | 1.321 | 36 | 108 | 1.888 | 4.470 |
| S4 | 20 | 2 | 103 | 79 | 3.007 | 3.011 | 3.075 |

Com 5 vezes menos usuários o S1 melhora a mediana (de 1.005 para 108 ms) e a cauda continua acima do alvo; o S4 não melhora porque cada consulta já passa do limite sozinha. A coluna "planejados" é o plano; a tabela principal é a rodada com os usuários do plano.

## SLO do plano contra a medição

| SLO | Alvo | Medido | Situação |
| --- | --- | --- | --- |
| Busca S1 | p95 até 200 ms, p99 até 500 ms | p95 3.402 ms, p99 5.857 ms, 3,6% de erro | não atende |
| Busca S2 | p95 até 200 ms, p99 até 500 ms | p95 44 ms, p99 204 ms, 0 erro | atende |
| Detalhe S5 | p95 até 50 ms com cache | p95 2 ms (taxa de acerto 81,6%) | atende |

Os alvos continuam os do plano. A ADR-0025 registra o valor medido do S1.

## Conclusão

A arquitetura de busca (FTS com GIN, keyset, estatísticas incrementais, cache do detalhe) segura 1,2 milhão de linhas nos caminhos que usam o índice: filtrada, por chave ou com termo seletivo. Dois casos ruins ficaram provados com número:

1. **Termo comum no S1.** A consulta ordena todas as linhas que casam; custa de 0,5 s a 2 s por busca e, com 50 usuários, passa do SLO e do `statement_timeout`. Opções a avaliar, nenhuma implementada nesta PR: limitar o ranking a um teto de candidatos, ou tratar à parte os termos de alta frequência (alvo próprio ou aviso de refinamento).
2. **Órgão com erro de digitação no S4.** A varredura sequencial de 1,2 milhão de linhas custa 2 s. A alternativa do ADR-0011 (tabela `search.organ`, uma linha por órgão, mantida pelo projetor) reduz a consulta a milhares de linhas e passa a ser a recomendação.

Pendências: refazer a medição com 3 execuções e mediana dos p95 quando a máquina estiver livre (RAM acima de 6 GB, sem swap), medir S5 sem cache para provar o ganho sobre o Postgres, e o S7 (ingestão com WireMock).
