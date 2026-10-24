# Radar de Licitações

Plataforma que coleta as licitações publicadas no PNCP (Portal Nacional de Contratações Públicas), mantém o histórico dos editais, oferece busca textual em português e envia e-mail quando um edital atende a uma busca salva.

## Estado

Os marcos M0 a M8 estão entregues e o M9 (camada 4) não foi feito. Cada capacidade tem código, teste ou benchmark e ADR; os números vêm de `make bench` e `make slo`, com data, hardware e rótulo real ou sintético.

- M0: sonda do PNCP e fundação do repositório.
- M1: coleta incremental e backfill, outbox transacional, Kafka e projeção.
- M2: busca textual em português, API REST e interface Thymeleaf com htmx, benchmark com 1,2 milhão de linhas.
- M3: alertas por e-mail para buscas salvas e resumo diário.
- M4: observabilidade, SLOs, caos com Toxiproxy, painel de operação e DLQ.
- M5: segurança (OIDC, papéis, chaves de API, checklist OWASP) e Redis (limite de requisições e cache do detalhe).
- M6: virtual threads na coleta, escolha do coletor de lixo e otimização guiada por profile.
- M7: imagem, compose como ambiente oficial, deploy com rollback, Helm e Terraform.
- M8: Avro com Apicurio, extração em `ingestor` e `search-api`, Debezium como alternativa ao relay e mensageria avançada (rebalance, backpressure, ordem).

O que foi cortado, e por quê, está nas notas de escopo da [ADR-0001](docs/adr/0001-registro-de-decisoes.md). Em resumo:

- M9 (gRPC ou GraphQL): não feito.
- Spring Cloud Contract: adiado, sem linha compatível com o Boot 4.1; o contrato OpenAPI é verificado por teste.
- Sem `release.yml` (não há GitHub Release com jar, SBOM e checksums) e sem OpenAPI no GitHub Pages.
- Disponibilidade da API sob caos: não medida no compose; o caos prova perda e duplicata zero em teste.
- A sonda do PNCP terminou no 429, sem as 24 h, sem a rampa de taxa e sem a madrugada.
- Sem JMH: o ponto quente era SQL, não código da JVM.
- O Helm não tem a forma dividida e recusa mais de uma réplica; o nightly é só a fumaça da busca (sem PIT completo, sem backfill real de 1 dia, sem dependency-check).
- Sem captura de tela de trace e sem snapshot do PNCP como asset de release; badges não se aplicam, porque o repositório é local.

## Arquitetura

Monólito modular com Spring Modulith e arquitetura hexagonal por contexto, com CQRS: escrita em JPA e leitura em jOOQ. Os eventos saem por Transactional Outbox e são publicados no Kafka, e consumidores idempotentes alimentam projeções reconstruíveis. As decisões e os motivos estão nas ADRs em `docs/adr/`.

## Como rodar

Precisa de Docker (com o plugin `compose`) e de uns 4 GB de RAM livres para a pilha inteira (cerca de 2,8 GiB medidos, ver `docs/benchmarks/compose-memory.md`).

```
make up      # cria .env e segredos, constrói a imagem se faltar e sobe tudo saudável (cerca de 2 min)
make seed    # dispara em segundo plano o carregamento de 1 dia de dados reais do PNCP (cerca de 30 s)
make down    # para o ambiente (CLEAN=1 make down também apaga os volumes)
```

`make logs` acompanha os logs (`SERVICE=app` para um só). `PROFILE=layer3 make up` sobe também o Apicurio; com `make schemas-register` e `RADAR_MESSAGING_FORMAT=avro` os eventos passam a sair em Avro (ADR-0024, `docs/benchmarks/schema/evolution.md`). `PROFILE=cdc make up` troca o relay por polling pelo Debezium (ADR-0018, `docs/benchmarks/cdc/polling-vs-debezium.md`). `PROFILE=split make up` troca o monólito por dois serviços, `ingestor` (coleta e relay, porta 8082) e `search-api` (busca, alertas, identidade e operação, porta 8080), a partir de `make image-split` (ADR-0028); `make e2e-split` roda o fluxo ponta a ponta nessa forma. Nesse perfil a coleta é do ingestor: `RADAR_APP_URL=http://localhost:8082 make seed` (ou `make backfill`); ao trocar de perfil rode `make down` antes.

| O quê | URL | Acesso |
| --- | --- | --- |
| Aplicação | http://localhost:8080 | login pelo Keycloak: `fornecedor` / `fornecedor` (papel supplier) |
| Grafana (dashboards, traces) | http://localhost:3000 | `admin` / `admin` |
| Mailpit (e-mails) | http://localhost:8025 | sem login |
| Keycloak | http://localhost:8180 | console: `admin` / `troque-me` (`infra/compose/.env`); usuário `operador` / `operador` (papel operator) |

Os dados do seed são públicos, do PNCP, coletados em 2026-10-22 (editais da modalidade 6 publicados em 2026-10-21, 1.633 linhas); o compose não acessa o PNCP real. Todos os valores acima são padrões de desenvolvimento, só para uso local.

Outros alvos: `make verify` (build completo: exige JDK 25 e Docker, porque os testes de integração sobem containers com Testcontainers e o build gera o modelo jOOQ a partir das migrations num Postgres descartável) e `make e2e` (sobe o compose e roda o fluxo ponta a ponta; exige as portas livres e a imagem, `make image`).

O resultado de `make bench` alimenta a seção Números e `docs/benchmarks/search/`; `make docs-check` confere que o bloco de números é o que `docs/benchmarks/latest.json` gera. O commit citado em cada medição é o commit do PR na `main` (`BENCH_COMMIT`, ver `scripts/render-metrics.sh`).

Imagem do monólito: 617 MB (medido por `scripts/image-size.sh` em 2026-10-24, sobre a imagem construída em 2026-10-23, depois do PR 6.2, com o G1 fixado). A medição anterior, 393 MB em 2026-10-22, era do M0 e não tem a busca, os alertas, o Redis nem o Avro.

### Reproduzir as medições

Cada alvo gera o artefato versionado ao lado do relatório. Com exceção de `make chaos` (Testcontainers) e `make replay` (usa a pilha já no ar), todos pedem a imagem (`make image`, ou `make image-split` para o perfil dividido). Rode um por vez, sem outra pilha nas portas fixas.

| Alvo | O que faz | Relatório |
| --- | --- | --- |
| `make bench` | busca com 1,2 milhão de linhas (dia real mais massa sintética), planos sem e com índices, Gatling S1 a S6 (cerca de 1 h, uns 6 GB livres) | `docs/benchmarks/search/` |
| `make bench-pool` | repete S1, S2 e S5 com o pool de busca em 10, 20 e 40 conexões | `docs/benchmarks/db/hikari.md` |
| `make slo` | SLOs com tráfego misto sintético sobre 200 mil linhas (15 min por padrão; `SLO_DURATION=1h` para o plano) | `docs/benchmarks/slo/` |
| `make profile GC=serial\|g1\|zgc\|all` | a mesma carga sob cada coletor de lixo, com JFR | `docs/benchmarks/jvm/` |
| `make replay` | com a pilha no ar, reconstrói o read model do offset 0 do tópico e imprime o relatório | `docs/benchmarks/replay/` |
| `make chaos` | 10 mil eventos com Toxiproxy; as perdas e as duplicatas impressas no fim devem ser 0 | `docs/benchmarks/chaos/` |
| `make deploy TAG=<tag>` e `make rollback` | troca a imagem do `app` com smoke e volta ao deploy anterior (ADR-0022) | `docs/runbook.md`, seção Rollback |
| `make e2e-split` | o fluxo ponta a ponta com `ingestor` e `search-api` (depois de `make image-split`) | ADR-0028 |

### Sonda do PNCP (histórico)

A sonda (`tools/pncp-probe`) rodou em 2026-10-22, das 17:02 às 19:44, em três execuções, a 0,5 req/s com `User-Agent` próprio. A API pública respondeu 429 a essa taxa e a sonda parou ali, como estava previsto: 2.646 requisições, sem as 24 h, sem a rampa de taxa e sem a madrugada. Ela não foi retomada. O que se mediu decidiu o recorte (ADR-0005), a estratégia incremental (ADR-0006) e a taxa de coleta, 0,2 req/s ([ADR-0017](docs/adr/0017-resiliencia-cliente-pncp.md)). O relatório está em [`docs/benchmarks/probe/2026-10-22.md`](docs/benchmarks/probe/2026-10-22.md).

`make probe` continua no repositório como evidência e agora exige `PROBE_CONFIRM=1`, porque rodá-la de novo volta a pressionar a API real. `make probe-report` gera o relatório a partir do JSONL gravado (`tools/pncp-probe/out`, fora do git).

## Interface

Busca em `/` (texto, UF, modalidade e "somente com propostas abertas"), detalhe em `/licitacoes/{id}` com o histórico de situação e o link para o edital no PNCP. As páginas são renderizadas no servidor com Thymeleaf e atualizadas com htmx servido pela própria aplicação (ADR-0014).

![Busca e detalhe de licitações](docs/img/interface.gif)

A captura mostra dados reais do PNCP, os editais da modalidade 6 publicados em 2026-10-21 (o dia versionado em `infra/compose/seed`, coletado em 2026-10-22). `make readme-gif` roda o teste de captura e a regera (precisa do ImageMagick).

`make e2e-ui` roda o teste da interface num Chromium (Playwright) contra a aplicação em processo com Postgres de teste; baixa o navegador na primeira vez e `PLAYWRIGHT_SKIP=1` pula o teste.

## Observabilidade

- Métricas: `GET /actuator/prometheus` (exige o papel `operator`). No compose a aplicação envia as métricas por OTLP ao `otel-lgtm`; os nomes usados nos dashboards e nas regras, e como chegam por OTLP, estão no ADR-0025.
- Traces: OpenTelemetry com envio OTLP para `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `http://localhost:4318`). Sem coletor o exportador registra avisos; para desligar só o envio, `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=false`. O trace de uma requisição HTTP continua pelo outbox e pelo registro Kafka (header `traceparent`). No compose, o Grafana do `otel-lgtm` (porta 3000) mostra os traces e os dashboards de `infra/grafana`.
- Operação: `docs/runbook.md` tem uma seção por alerta (sintoma, impacto, diagnóstico, ação, escalonamento) e as operações rotineiras; o incidente simulado de partição ausente está em `docs/post-mortem/`.
- Logs: texto no desenvolvimento; JSON na imagem (perfil `json`), com `traceId`, `spanId` e `correlationId`. O cabeçalho `X-Correlation-Id` é aceito na entrada e devolvido na resposta.

## Números

<!-- bench:start -->
Medido em 2026-10-23 (commit `2ce350f`), AMD Ryzen 5 5600GT with Radeon Graphics, 12 threads, 11,6 GiB de RAM, Postgres 18.6, 1.200.000 linhas de busca: 1.633 reais do PNCP (dia versionado em `infra/compose/seed`) e 1.198.367 sintéticas. Rótulo de todo número abaixo: **inclui dados sintéticos (1.198.367 linhas)**. Uma execução por cenário, 1 min de aquecimento descartado e 3 min medidos; cada usuário espera 1 s entre requisições.

| Cenário | Usuários | Requisições | Erros | p50 (ms) | p95 (ms) | p99 (ms) | req/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 text search | 50 | 8.662 | 0 | 34 | 83 | 122 | 48,1 |
| S2 filtered search | 50 | 8.925 | 0 | 6 | 22 | 46 | 49,6 |
| S3 keyset p1 (medido em 2026-10-23, commit `1496441`) | 10 | 300 | 0 | 2 | 3 | 5 | 1,7 |
| S3 offset p1 (medido em 2026-10-23, commit `1496441`) | 10 | 300 | 0 | 2 | 4 | 6 | 1,7 |
| S3 keyset p100 (medido em 2026-10-23, commit `1496441`) | 10 | 300 | 0 | 2 | 3 | 6 | 1,7 |
| S3 offset p100 (medido em 2026-10-23, commit `1496441`) | 10 | 300 | 0 | 5 | 7 | 8 | 1,7 |
| S3 keyset p1000 (medido em 2026-10-23, commit `1496441`) | 10 | 300 | 0 | 2 | 3 | 7 | 1,7 |
| S3 offset p1000 (medido em 2026-10-23, commit `1496441`) | 10 | 300 | 0 | 31 | 38 | 48 | 1,7 |
| S4 organ typo | 20 | 3.545 | 0 | 13 | 18 | 23 | 19,7 |
| S5 detail | 100 | 17.975 | 0 | 1 | 2 | 5 | 99,9 |
| S6 stats by uf (medido em 2026-10-23, commit `1496441`) | 10 | 900 | 0 | 2 | 2 | 3 | 5 |
| S6 stats by month (medido em 2026-10-23, commit `1496441`) | 10 | 900 | 0 | 2 | 2 | 3 | 5 |

- Cache do detalhe (S5): 19.518 acertos e 4.444 faltas, taxa de acerto de 81,5%.
- SLO de busca (S1 e S2, p95 até 200 ms e p99 até 500 ms): S1 atende, S2 atende.
- SLO de detalhe (S5, p95 até 50 ms): atende.
- Planos, hardware e leitura completa: `docs/benchmarks/search/2026-10-23-pr63/README.md`.
<!-- bench:end -->

### SLOs medidos

<!-- slo:start -->
Medido em 2026-10-23 (commit `441ba19`), AMD Ryzen 5 5600GT with Radeon Graphics, 12 threads, 11,6 GiB de RAM, Postgres 18.6, 200.000 linhas de busca: 1.633 reais do PNCP e 198.367 sintéticas, mais 1.370 editais do PNCP simulado coletados durante a medição. Rótulo de todo número abaixo: **sintético** (massa e tráfego de ingestão). Janela medida de 15 min, depois de 60 s de aquecimento descartado; coleta incremental a cada 30 s (o padrão da aplicação é 5 min); carga mista S1, S2, S5 e S6 com 5, 5, 10 e 2 usuários.

| SLO | Alvo | Medido | Atende |
| --- | --- | ---: | --- |
| Frescor interno: coleta até a projeção | p95 até 30 s | 1,41 s | sim |
| Frescor ponta a ponta: dataAtualizacaoGlobal até visível na busca | p95 até o intervalo de polling mais 1 min (90 s com polling de 30 s) | 28,17 s | sim |
| Alerta: match até o SMTP aceitar | p95 até 60 s | 0,92 s | sim |
| Busca S1 text search: p95 | p95 até 200 ms | 18 ms | sim |
| Busca S1 text search: p99 | p99 até 500 ms | 86 ms | sim |
| Busca S2 filtered search: p95 | p95 até 200 ms | 6 ms | sim |
| Busca S2 filtered search: p99 | p99 até 500 ms | 18 ms | sim |
| Detalhe S5 com cache: p95 | p95 até 50 ms | 3 ms | sim |
| Outbox: idade da mensagem mais antiga, p99 | p99 até 5 s | 0,00 s | sim |
| Disponibilidade da API durante caos com Toxiproxy | 99,5% em 1 h | não medido | não medido |
| Disponibilidade da busca sem caos (informativo) | sem alvo próprio (o alvo do plano vale com caos) | 100,000 % | sem alvo |
| Busca vista pelo servidor: p95 (informativo) | sem alvo próprio | 14,8 ms | sem alvo |
| Estatísticas S6: p95 (informativo) | sem alvo no plano | 2 ms | sem alvo |

- Leitura completa e limites: `docs/benchmarks/slo/2026-10-23.md`.
<!-- slo:end -->

A latência de S1 e S2 da tabela acima vem de `make slo`, com 200 mil linhas de busca; por isso é bem menor que a da seção Números, que vem de `make bench` com 1,2 milhão de linhas (S1: p95 de 83 ms contra 18 ms). O SLO de busca (p95 até 200 ms) vale nos dois volumes; o número de referência para o volume cheio é o do `make bench`.

## Fora do escopo

- Nuvem permanente.
- Provedor de e-mail externo.
- MFA e WebAuthn.
- Contratos, atas e itens: a sonda não deu base para liberar (`niFornecedor` pode ser CPF, `atas` com timeout; ver `docs/benchmarks/probe/2026-10-22.md`, S6).
- gRPC e GraphQL (camada 4, M9): não feito.

## Licença

Distribuído sob a licença MIT. Veja o arquivo [LICENSE](LICENSE).
