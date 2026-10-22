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

`make logs` acompanha os logs (`SERVICE=app` para um só). `PROFILE=layer3 make up` sobe também o Apicurio.

| O quê | URL | Acesso |
| --- | --- | --- |
| Aplicação | http://localhost:8080 | login pelo Keycloak: `fornecedor` / `fornecedor` (papel supplier) |
| Grafana (dashboards, traces) | http://localhost:3000 | `admin` / `admin` |
| Mailpit (e-mails) | http://localhost:8025 | sem login |
| Keycloak | http://localhost:8180 | console: `admin` / `troque-me` (`infra/compose/.env`); usuário `operador` / `operador` (papel operator) |

Os dados do seed são públicos, do PNCP, coletados em 2026-10-22 (editais da modalidade 6 publicados em 2026-10-21, 1.633 linhas); o compose não acessa o PNCP real. Todos os valores acima são padrões de desenvolvimento, só para uso local.

Outros alvos: `make verify` (build completo: exige JDK 25 e Docker, porque os testes de integração sobem containers com Testcontainers e o build gera o modelo jOOQ a partir das migrations num Postgres descartável) e `make e2e` (sobe o compose e roda o fluxo ponta a ponta; exige as portas livres e a imagem, `make image`).

Imagem: 393 MB (medido por scripts/image-size.sh em 2026-10-20).

### Sonda do PNCP

A sonda (`tools/pncp-probe`) mede por 24 h o comportamento real da API pública antes de congelar taxa, intervalo de polling e recorte de dados. Ela se identifica com `User-Agent` próprio e mantém 0,5 req/s (1 requisição a cada 2 s). Só a rampa única S3, às 3h, sobe até 4 req/s por cerca de 60 s, e nada passa de 4 req/s. Para na primeira resposta 429 ou 403 da API de dados (o `robots.txt` só é registrado).

```
make probe          # constrói o jar e roda em segundo plano (log e PID em tools/pncp-probe/out)
make probe-report   # gera docs/benchmarks/probe/<data>.md a partir do JSONL gravado
```

## Observabilidade

- Métricas: `GET /actuator/prometheus` (exige o papel `operator`). No compose a aplicação envia as métricas por OTLP ao `otel-lgtm`; os nomes usados nos dashboards e nas regras, e como chegam por OTLP, estão no ADR-0025.
- Traces: OpenTelemetry com envio OTLP para `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `http://localhost:4318`). Sem coletor o exportador registra avisos; para desligar só o envio, `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=false`. O trace de uma requisição HTTP continua pelo outbox e pelo registro Kafka (header `traceparent`). No compose, o Grafana do `otel-lgtm` (porta 3000) mostra os traces e os dashboards de `infra/grafana`.
- Logs: texto no desenvolvimento; JSON na imagem (perfil `json`), com `traceId`, `spanId` e `correlationId`. O cabeçalho `X-Correlation-Id` é aceito na entrada e devolvido na resposta.

## Números

<!-- bench:start -->
Nenhum número publicado ainda. Todo número desta seção vem de `make bench` ou `make slo`, com data, hardware e rótulo real/sintético.
<!-- bench:end -->

## Fora do escopo

- Nuvem permanente.
- Provedor de e-mail externo.
- MFA e WebAuthn.
- Contratos, atas e itens até a sonda provar viabilidade.
- gRPC e GraphQL (camada 4), só se houver folga.

## Licença

Distribuído sob a licença MIT. Veja o arquivo [LICENSE](LICENSE).
