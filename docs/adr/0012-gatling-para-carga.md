# ADR-0012: Gatling (DSL Java) para carga HTTP, com o benchmark rodando no compose

- Status: aceita
- Data: 2026-10-23

## Contexto

O plano promete números medidos para a busca com mais de 1 milhão de linhas (ADR-0011) e para o detalhe com cache (ADR-0027). Falta escolher a ferramenta de carga e o formato do experimento. As restrições: o projeto é Java 25 com Maven, o ambiente oficial é o compose (ADR-0022), a máquina de desenvolvimento tem 11 GiB de RAM e 12 threads, e todo número publicado precisa poder ser refeito por um comando.

## Decisão

Gatling com a DSL Java, num módulo Maven `perf/` fora do build padrão (perfil `-Pperf`), e `make bench` como ponto de entrada.

- Seis simulações, uma por cenário do plano (S1 a S6), em `perf/src/test/java/br/dev/miguelsanzbr/radar/perf/`. Os insumos (200 termos reais, nomes de órgão com erro de digitação, UFs) são versionados em `perf/src/test/resources/` e regenerados por `scripts/bench-terms.sh` a partir do dia real do seed.
- Carga em modelo fechado: N usuários sempre em andamento, cada um com 1 s de pausa entre requisições. Cada cenário roda duas fases seguidas, 1 min de aquecimento descartado (as requisições levam o sufixo `(warmup)` no nome) e 3 min medidos.
- O `make bench` (`scripts/bench.sh`) sobe o compose com `infra/compose/docker-compose.bench.yml`, carrega o dia real e completa com massa sintética rotulada (`tools/synth`, ADR-0005), compara os planos sem e com os índices (`scripts/explain/`), roda o Gatling e escreve `docs/benchmarks/search/<data>/`, `docs/benchmarks/latest.json` e o bloco de números do README. `make docs-check` falha se o bloco divergir do `latest.json`.
- A paginação por `OFFSET` do S3 só existe com `radar.bench.offset-endpoint=true` (um `@ConditionalOnProperty` em `BenchOffsetController`): fora do contrato OpenAPI, fora da matriz de autorização e ausente por padrão, com teste.
- O SQL puro (sem HTTP) é medido por `EXPLAIN (ANALYZE, BUFFERS)`, não por `pgbench`: a pergunta é qual plano o Postgres escolhe, e o Gatling já mede o caminho completo.

## Alternativas rejeitadas (e por quê)

- k6: o roteiro em JavaScript não compartilha o build, o JDK nem o ambiente do resto do projeto, e a decisão de pinar versões e rodar offline já está resolvida pelo Maven.
- JMeter: roteiros em XML ruins de revisar em diff e de versionar.
- `pgbench` com scripts próprios: útil para SQL isolado, mas aqui o interesse é a latência fim a fim; o plano de cada consulta já sai do `EXPLAIN`.
- Modelo aberto (taxa fixa de chegadas): mede melhor a saturação, mas o plano fala em "usuários", e o modelo fechado se auto-limita quando a máquina compartilhada engasga em vez de acumular fila.

## Consequências

- O módulo `perf` só compila com `-Pperf`: o `./mvnw verify` padrão não o enxerga.
- O Gatling 3.15 não grava `stats.json`: `scripts/gatling-stats.sh` lê a tabela de estatísticas do `index.html` do relatório (formato fixado pela versão pinada).
- O benchmark mede a máquina onde roda; o relatório declara hardware, configuração do Postgres e quanta carga a máquina aguentou (usuários planejados e usados).

## Prova

- `make bench` rodou uma vez de ponta a ponta em 2026-10-23, com 1,2 milhão de linhas: `docs/benchmarks/search/2026-10-23/README.md` e `docs/benchmarks/latest.json`. As seis simulações rodaram com os usuários do plano (S1 50, S2 50, S3 10, S4 20, S5 100, S6 10), 1 min de aquecimento descartado e 3 min medidos; S1 e S4 tiveram ainda uma rodada com menos usuários (10 e 2) para separar o custo da consulta da saturação do host.
- O endpoint de `OFFSET` do S3 é provado por `BenchOffsetEndpointIT` (mesmas linhas que a página keyset equivalente, cursor seguinte leva à página seguinte, limites) e `BenchOffsetEndpointSwitchTest` (o bean não existe por padrão nem com a chave em `false`).
- O resumo em `scripts/render-metrics.sh self-test` e `make docs-check` (CI) prova que o bloco de números do README sai só do `latest.json`.
- Fumaça noturna: `.github/workflows/nightly.yml` (1 min de S1 com 50 mil linhas), definida e não executada na PR 2.7.
- Limites desta medição: uma execução por cenário (o plano pede três e a mediana dos p95), máquina compartilhada com o ambiente gráfico do desenvolvedor, Postgres com 2 GiB em vez dos 256 MiB do compose de desenvolvimento.
