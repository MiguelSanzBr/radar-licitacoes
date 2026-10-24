# Radar de Licitações

Plataforma que coleta as licitações publicadas no PNCP (Portal Nacional de Contratações Públicas), mantém o histórico dos editais, oferece busca textual em português e envia e-mail quando um edital atende a uma busca salva.

## Estado

Em construção. O projeto está no marco M0 (sonda de coleta e fundação do repositório). Nenhuma funcionalidade para usuário está pronta ainda.

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

`make bench` mede a busca com 1,2 milhão de linhas (carrega o dia real e completa com massa sintética rotulada, compara planos sem e com índices e roda o Gatling S1 a S6); o resultado alimenta a seção Números e `docs/benchmarks/search/`. `make docs-check` confere que o bloco de números é o que `docs/benchmarks/latest.json` gera.

Imagem: 393 MB (medido por scripts/image-size.sh em 2026-10-20).

### Sonda do PNCP

A sonda (`tools/pncp-probe`) mede por 24 h o comportamento real da API pública antes de congelar taxa, intervalo de polling e recorte de dados. Ela se identifica com `User-Agent` próprio e mantém 0,5 req/s (1 requisição a cada 2 s). Só a rampa única S3, às 3h, sobe até 4 req/s por cerca de 60 s, e nada passa de 4 req/s. Para na primeira resposta 429 ou 403 da API de dados (o `robots.txt` só é registrado).

```
make probe          # constrói o jar e roda em segundo plano (log e PID em tools/pncp-probe/out)
make probe-report   # gera docs/benchmarks/probe/<data>.md a partir do JSONL gravado
```

## Interface

Busca em `/` (texto, UF, modalidade e "somente com propostas abertas"), detalhe em `/licitacoes/{id}` com o histórico de situação e o link para o edital no PNCP. As páginas são renderizadas no servidor com Thymeleaf e atualizadas com htmx servido pela própria aplicação (ADR-0014).

Captura gerada no PR 7.2.

`make e2e-ui` roda o teste da interface num Chromium (Playwright) contra a aplicação em processo com Postgres de teste; baixa o navegador na primeira vez e `PLAYWRIGHT_SKIP=1` pula o teste.

## Observabilidade

- Métricas: `GET /actuator/prometheus` (exige o papel `operator`). No compose a aplicação envia as métricas por OTLP ao `otel-lgtm`; os nomes usados nos dashboards e nas regras, e como chegam por OTLP, estão no ADR-0025.
- Traces: OpenTelemetry com envio OTLP para `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `http://localhost:4318`). Sem coletor o exportador registra avisos; para desligar só o envio, `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=false`. O trace de uma requisição HTTP continua pelo outbox e pelo registro Kafka (header `traceparent`). No compose, o Grafana do `otel-lgtm` (porta 3000) mostra os traces e os dashboards de `infra/grafana`.
- Operação: `docs/runbook.md` tem uma seção por alerta (sintoma, impacto, diagnóstico, ação, escalonamento) e as operações rotineiras; o incidente simulado de partição ausente está em `docs/post-mortem/`.
- Logs: texto no desenvolvimento; JSON na imagem (perfil `json`), com `traceId`, `spanId` e `correlationId`. O cabeçalho `X-Correlation-Id` é aceito na entrada e devolvido na resposta.

## Números

<!-- bench:start -->
Medido em 2026-10-23 (commit `a05da33`), AMD Ryzen 5 5600GT with Radeon Graphics, 12 threads, 11,6 GiB de RAM, Postgres 18.6, 1.200.000 linhas de busca: 1.633 reais do PNCP (dia versionado em `infra/compose/seed`) e 1.198.367 sintéticas. Rótulo de todo número abaixo: **inclui dados sintéticos (1.198.367 linhas)**. Uma execução por cenário, 1 min de aquecimento descartado e 3 min medidos; cada usuário espera 1 s entre requisições.

| Cenário | Usuários | Requisições | Erros | p50 (ms) | p95 (ms) | p99 (ms) | req/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 text search | 50 | 8.662 | 0 | 34 | 83 | 122 | 48,1 |
| S2 filtered search | 50 | 8.925 | 0 | 6 | 22 | 46 | 49,6 |
| S3 keyset p1 (medido em 2026-10-23, commit `c82905b`) | 10 | 300 | 0 | 2 | 3 | 5 | 1,7 |
| S3 offset p1 (medido em 2026-10-23, commit `c82905b`) | 10 | 300 | 0 | 2 | 4 | 6 | 1,7 |
| S3 keyset p100 (medido em 2026-10-23, commit `c82905b`) | 10 | 300 | 0 | 2 | 3 | 6 | 1,7 |
| S3 offset p100 (medido em 2026-10-23, commit `c82905b`) | 10 | 300 | 0 | 5 | 7 | 8 | 1,7 |
| S3 keyset p1000 (medido em 2026-10-23, commit `c82905b`) | 10 | 300 | 0 | 2 | 3 | 7 | 1,7 |
| S3 offset p1000 (medido em 2026-10-23, commit `c82905b`) | 10 | 300 | 0 | 31 | 38 | 48 | 1,7 |
| S4 organ typo | 20 | 3.545 | 0 | 13 | 18 | 23 | 19,7 |
| S5 detail | 100 | 17.975 | 0 | 1 | 2 | 5 | 99,9 |
| S6 stats by uf (medido em 2026-10-23, commit `c82905b`) | 10 | 900 | 0 | 2 | 2 | 3 | 5 |
| S6 stats by month (medido em 2026-10-23, commit `c82905b`) | 10 | 900 | 0 | 2 | 2 | 3 | 5 |

- Cache do detalhe (S5): 19.518 acertos e 4.444 faltas, taxa de acerto de 81,5%.
- SLO de busca (S1 e S2, p95 até 200 ms e p99 até 500 ms): S1 atende, S2 atende.
- SLO de detalhe (S5, p95 até 50 ms): atende.
- Planos, hardware e leitura completa: `docs/benchmarks/search/2026-10-23-pr63/README.md`.
<!-- bench:end -->

## Fora do escopo

- Nuvem permanente.
- Provedor de e-mail externo.
- MFA e WebAuthn.
- Contratos, atas e itens: a sonda não deu base para liberar (`niFornecedor` pode ser CPF, `atas` com timeout; ver `docs/benchmarks/probe/2026-10-22.md`, S6).
- gRPC e GraphQL (camada 4), só se houver folga.

## Licença

Distribuído sob a licença MIT. Veja o arquivo [LICENSE](LICENSE).
