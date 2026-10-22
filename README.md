# Radar de Licitações

Plataforma que coleta as licitações publicadas no PNCP (Portal Nacional de Contratações Públicas), mantém o histórico dos editais, oferece busca textual em português e envia e-mail quando um edital atende a uma busca salva.

## Estado

Em construção. O projeto está no marco M0 (sonda de coleta e fundação do repositório). Nenhuma funcionalidade para usuário está pronta ainda.

## Arquitetura

Monólito modular com Spring Modulith e arquitetura hexagonal por contexto, com CQRS: escrita em JPA e leitura em jOOQ. Os eventos saem por Transactional Outbox e são publicados no Kafka, e consumidores idempotentes alimentam projeções reconstruíveis. As decisões e os motivos estão nas ADRs em `docs/adr/`.

## Como rodar

Por enquanto, o alvo disponível é a verificação completa do build:

```
make verify
```

É preciso JDK 25 e Docker (os testes de integração sobem containers com Testcontainers).

Imagem: 393 MB (medido por scripts/image-size.sh em 2026-10-20).

### Sonda do PNCP

A sonda (`tools/pncp-probe`) mede por 24 h o comportamento real da API pública antes de congelar taxa, intervalo de polling e recorte de dados. Ela se identifica com `User-Agent` próprio e mantém 0,5 req/s (1 requisição a cada 2 s). Só a rampa única S3, às 3h, sobe até 4 req/s por cerca de 60 s, e nada passa de 4 req/s. Para na primeira resposta 429 ou 403 da API de dados (o `robots.txt` só é registrado).

```
make probe          # constrói o jar e roda em segundo plano (log e PID em tools/pncp-probe/out)
make probe-report   # gera docs/benchmarks/probe/<data>.md a partir do JSONL gravado
```

## Observabilidade

- Métricas: `GET /actuator/prometheus` (aberto até a tarefa 5.1, que o restringe ao papel de operador). Os nomes usados nos dashboards e nas regras estão no ADR-0025.
- Traces: OpenTelemetry com envio OTLP para `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `http://localhost:4318`). Sem coletor o exportador registra avisos; para desligar só o envio, `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=false`. O trace de uma requisição HTTP continua pelo outbox e pelo registro Kafka (header `traceparent`). Quando o compose existir (tarefa 7.2), o Grafana do `otel-lgtm` mostra o trace e o Prometheus faz o scrape do app.
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
