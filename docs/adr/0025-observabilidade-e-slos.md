# ADR-0025: Observabilidade com Micrometer e LGTM, e SLOs com burn rate

- Status: proposta
- Data: 2026-10-20

## Contexto

O radar coleta dados de uma API externa (PNCP), move eventos pelo outbox e pelo Kafka, projeta leituras para a busca e envia alertas por e-mail. Uma falha em qualquer etapa só aparece para quem usa a busca, muitas vezes horas depois. Precisamos medir o frescor dos dados, a latência e a disponibilidade da busca e a entrega dos alertas, e saber quando o orçamento de erro está sendo consumido rápido demais.

Os alvos desta decisão vêm da seção 7.4 do plano. Nenhum deles foi medido ainda.

## Decisão

- Métricas com Micrometer, expostas em `/actuator/prometheus`. O tracing com a ponte OpenTelemetry e o envio OTLP para o stack LGTM ficam na tarefa 4.1.
- Prometheus faz scrape do app a cada 15 s (`infra/prometheus/prometheus.yml`); no compose o caminho é o push por OTLP (no compose, ver abaixo).
- Quatro dashboards versionados em `infra/grafana/dashboards/` (coleta, outbox e Kafka, busca e API, alertas). Cada painel lê o datasource pela variável `datasource`, sem UID fixo.
- Regras de SLO em `infra/prometheus/rules/slo.yml`: burn rate com duas janelas. Alerta crítico com 14,4 vezes o ritmo aceito em 1 h e 5 min (consome cerca de 2% do orçamento de 30 dias em uma hora). Alerta de aviso com 6 vezes em 6 h e 30 min (cerca de 5% do orçamento). Alertas de limiar simples para outbox, projeção e entrega de alerta, com `for` de 10 min.
- Os alertas têm `severity`, `summary` e `runbook_url` apontando para `docs/runbook.md`.
- Dois alertas de saúde do próprio scrape: `RadarTargetDown` (`up{job="radar"} == 0` por 2 min, crítico) e `RadarSemTrafego` (nenhuma requisição à busca por 15 min, aviso, com `absent()`).
- Alerta de limiar `ParticaoFutura` (`ingest_partitions_ahead < 1` por 1 h, crítico): o mês seguinte não tem partição em `ingest.procurement`. É o único alerta que vigia um erro silencioso, porque a falha de insert não move nenhuma outra métrica (`docs/post-mortem/2026-10-22-particao-ausente.md`).
- Alertas `PncpBloqueado` (`pncp_blocked_until_seconds > 0`, aviso) e `PncpBloqueadoPorMuito` (a mesma condição por 2 h, crítico): o PNCP bloqueou o cliente com 429 ou 403 e a coleta está em pausa (ADR-0017). O gauge vale o fim do bloqueio em segundos desde 1970 e volta a 0 sozinho quando ele acaba; o crítico só dispara se a pausa não bastar.
- Testes das regras com `promtool test rules` (`infra/prometheus/rules/slo_test.yml`): cada alerta dispara com séries sintéticas que violam o alvo, e nenhum dispara com tráfego saudável.

Alvos propostos, não medidos:

| SLO | Alvo proposto | Situação |
|---|---|---|
| Frescor interno: `fetched_at` até `projected_at` | p95 até 30 s | proposto, não medido |
| Frescor ponta a ponta: `dataAtualizacaoGlobal` até visível na busca | p95 até o intervalo de polling mais 1 min | proposto, não medido (medido em `make slo`) |
| Alerta: `projected_at` até o SMTP aceitar | p95 até 60 s | proposto, não medido |
| Busca S1 e S2 com 1,2 milhão de linhas | p95 até 200 ms, p99 até 500 ms | medido (ver abaixo): na PR 2.7 S2 atendia e S1 não; depois da PR 6.3 os dois atendem (S1 p95 83 ms, p99 122 ms) |
| Detalhe S5 com cache | p95 até 50 ms | medido em 2026-10-23: atende (p95 de 2 ms) |
| Outbox | `outbox_oldest_age_seconds` p99 até 5 s | proposto, não medido |
| Disponibilidade da API durante caos com Toxiproxy | 99,5% em 1 h | proposto, não medido (Gatling em `make slo`) |

Duas conversões feitas nas regras:

- Disponibilidade da busca: 99,5% de respostas sem 5xx em `/api/v1/procurements`.
- Latência da busca: "p95 até 200 ms" vira "pelo menos 95% das respostas abaixo de 200 ms". As duas formas são equivalentes, mas a segunda é o que o histograma com bucket `le="0.2"` mede diretamente. O p99 de 500 ms não tem alerta de burn rate; aparece no painel de busca.

A configuração de buckets fica em `core/src/main/resources/application.properties`: `management.metrics.distribution.slo.http.server.requests=50ms,200ms,500ms`, com `percentiles-histogram` ligado para o `histogram_quantile` do painel. A projeção e a entrega de alerta usam histogramas que entram na tarefa 4.1, com a mesma configuração de distribuição.

Regra para os alvos: alvo que a medição não confirmar é ajustado no plano e registrado neste ADR com o valor medido. Nunca o contrário, ou seja, não se ajusta a medição para caber no alvo.

## Rastreamento e logs

Implementado na tarefa 4.1.

- Ponte OpenTelemetry pelo `spring-boot-starter-opentelemetry` (Micrometer Tracing e exportador OTLP gerenciados pelo Boot). Amostragem 1.0 (`management.tracing.sampling.probability`), reduzida por ambiente com `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`. O endpoint OTLP vem de `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `http://localhost:4318`). Só os spans saem por OTLP: métricas saem por scrape (`/actuator/prometheus`) e logs por stdout, então a exportação OTLP de métricas e de logs fica desligada.
- O `traceparent` atravessa o outbox. `JdbcOutboxAppender` grava o `traceparent` do span ativo no `headers` da linha, na mesma transação do agregado. O relay roda em outra thread e não tem trace próprio: `KafkaEventPublisher` abre um span "outbox publish" filho do `traceparent` gravado e envia dentro dele. Com `spring.kafka.template.observation-enabled=true`, o span de produção do `KafkaTemplate` vira filho desse span, e o header `traceparent` do registro Kafka carrega o `traceId` ORIGINAL da requisição (o `spanId` é o do produtor). O envelope JSON mantém o `traceparent` gravado na linha. Consumidores usam `spring.kafka.listener.observation-enabled=true` e continuam o trace; quem não usa observação de listener pode usar `TraceparentExtractor` (pacote `observability`).
- Logs JSON com o formato `logstash` do Boot, ligado pelo perfil `json` (a imagem define `SPRING_PROFILES_INCLUDE=json`; testes e desenvolvimento ficam em texto). O MDC leva `traceId`, `spanId` e `correlationId`. `CorrelationIdFilter` lê `X-Correlation-Id` (só `[A-Za-z0-9._-]` até 64 caracteres, senão descarta) ou gera um UUID v7, e devolve o valor no cabeçalho da resposta.
- Em testes o Boot desliga a exportação de traces e, junto, a propagação W3C; os testes que precisam de propagação usam `@AutoConfigureTracing` com `management.tracing.export.otlp.enabled=false`.
- Desde a tarefa 5.1 o `/actuator/prometheus` exige o papel `operator` (ADR-0019). No compose não há scrape: a aplicação envia as métricas por OTLP ao `otel-lgtm` (ADR-0022), e o `prometheus.yml` fica para o Kubernetes, onde o scrape precisa de um token de operador montado como arquivo (bloco `authorization` comentado no arquivo).

Métricas por tarefa (nomes fixos, os mesmos dos dashboards):

| Métrica | Tarefa | Situação |
|---|---|---|
| `pncp_items_fetched_total` | 1.4 | existe |
| `outbox_pending`, `outbox_oldest_age_seconds`, `outbox_poison_pending`, `outbox_poison_total` | 1.6 | existe |
| `dlq_messages_total{topic}` | 4.1 registra em zero para os tópicos `.dlq`; 4.3 incrementa a cada registro enviado à DLQ | existe |
| `http_server_requests_seconds_*` (buckets 50 ms, 200 ms, 500 ms) | 4.1 | existe |
| `projection_lag_seconds` | 1.9 | pendente |
| `kafka_consumer_lag` | 1.9 | pendente |
| `alert_delivery_latency_seconds` | 3.2 | pendente |

Prova: `TracePropagationIT` faz uma requisição HTTP que grava um edital e seus eventos no outbox, roda o relay e lê o Kafka: o `traceId` do span de servidor é o mesmo na linha do outbox, no header do registro Kafka e no MDC do handler. `PrometheusEndpointIT` faz o scrape e confere os nomes. As tarefas 1.9 e 3.2 estendem esse teste até a projeção e o alerta e devem mantê-lo verde.

### Nomes das métricas sob OTLP (verificado no compose, 2026-10-22)

Com o envio por OTLP, o Prometheus do `otel-lgtm` traduz os nomes. Consultado depois de `make up` e `make seed`:

- Contadores mantêm o sufixo `_total` sem duplicar (`ingest_items_total`, `pncp_items_fetched_total`, `dlq_messages_total`, `outbox_poison_total`). Medidores (`outbox_pending`, `outbox_oldest_age_seconds`, `resilience4j_circuitbreaker_state`) chegam com o mesmo nome.
- Timers e histogramas saem em milissegundos por padrão e chegam como `*_milliseconds_*`, o que quebraria todo `*_seconds_*` dos dashboards e das regras. A correção é `management.otlp.metrics.export.base-time-unit=seconds` (variável `MANAGEMENT_OTLP_METRICS_EXPORT_BASE_TIME_UNIT` no compose); com ela chegam `http_server_requests_seconds_{bucket,count,sum}` e `pncp_request_duration_seconds_*`, com os buckets `le="0.05"`, `"0.2"` e `"0.5"` que o SLO de latência usa.
- Os rótulos de negócio (`uri`, `status`, `endpoint`, `outcome`, `kind`) são os mesmos. O OTLP acrescenta `service_name` (`radar-licitacoes`, de `spring.application.name`) e `service_instance_id`; o rótulo `application=radar` continua.
- Diferenças que não são de nome: o `up{job="radar"}` do alerta `RadarTargetDown` não existe sob push (a série `up` do `otel-lgtm` é só a do coletor, com `job="otelcol-contrib"`); com push, a ausência de dados aparece como `absent()` (o `RadarSemTrafego` continua válido). As regras de SLO (`rules/slo.yml`) não são carregadas no Prometheus do `otel-lgtm` (a imagem fixa o `prometheus.yaml` e não tem Alertmanager): os dashboards funcionam, os alertas valem no Kubernetes, onde o Prometheus carrega o arquivo.
- Logs: o Boot só exporta logs por OTLP com o appender do OpenTelemetry no Logback, que o projeto não tem. No compose os logs saem por stdout (`make logs`) e o Loki do `otel-lgtm` fica vazio.

## Alternativas rejeitadas (e por quê)

- Alerta por limiar fixo de latência (por exemplo, p95 acima de 200 ms por 5 min): dispara com ruído em picos curtos e não diz quanto do orçamento de erro já foi consumido. Mantido só para os alertas de atraso de outbox, projeção e entrega, onde não há orçamento de erro.
- Só painéis, sem alerta: ninguém olha painel fora do horário de trabalho. O alerta precisa existir antes de o dashboard ser útil.
- Serviço pago de observabilidade (Datadog, New Relic): custo mensal e dependência de fornecedor para um projeto que precisa rodar no compose. O stack LGTM roda localmente.
- Medir os SLOs só com Gatling: mede o cenário de carga, não o tráfego real. Gatling continua no `make slo` para os números de carga; as regras de burn rate cobrem o que acontece em execução.

## Consequências

- O código ganha a configuração de buckets e as métricas com nomes fixos. Mudar um nome de métrica quebra dashboards e regras ao mesmo tempo, por isso os nomes estão listados no plano (seção 4).
- O CI ganha o job `observability`, que roda `make lint-observability`: validação dos dashboards (JSON, variável de datasource, sem UID fixo), `promtool check config`, `promtool check rules` e `promtool test rules`. A imagem do Prometheus é fixada em `prom/prometheus:v3.13.4`.
- Os links de `runbook_url` apontam para âncoras de `docs/runbook.md` que só existem na tarefa 4.5. Até lá, os links estão quebrados de propósito.
- O endpoint `/actuator/prometheus` depende das dependências de Micrometer e Actuator, que entram na tarefa 4.1. Enquanto isso, o scrape não tem alvo que responda.
- Roteamento dos alertas (Alertmanager, destinos como e-mail ou chat) ainda não está configurado. Por enquanto os alertas aparecem só no Prometheus: `prometheus.yml` tem `rule_files` e não tem bloco `alerting`. Configurar o Alertmanager fica para tarefa posterior.
- `make slo` (tarefa posterior) preenche os valores medidos e registra os ajustes neste ADR.

### Medido na PR 2.7 (`make bench`, 2026-10-23, 1,2 milhão de linhas, inclui dados sintéticos, 1.198.367 linhas)

Gatling com 1 min de aquecimento descartado e 3 min medidos, uma execução por cenário, na máquina local descrita em `docs/benchmarks/search/2026-10-23/env.md`:

| Cenário | Usuários | p50 | p95 | p99 | Erros | Alvo |
|---|---|---|---|---|---|---|
| S1 busca textual | 50 | 1.005 ms | 3.402 ms | 5.857 ms | 142 de 3.943 | **não atende** (p95 200 ms, p99 500 ms) |
| S2 busca com UF, modalidade e `open=true` | 50 | 8 ms | 44 ms | 204 ms | 0 | atende |
| S5 detalhe | 100 | 1 ms | 2 ms | 57 ms | 0 | atende (p95 50 ms) |

O alvo do S1 **não foi alterado**: a medição não o confirma. A causa é a busca com termo comum, que precisa ordenar centenas de milhares de linhas por `ts_rank_cd` (ADR-0011, "Prova do benchmark"); com 10 usuários o S1 deu p50 de 108 ms e p95 de 1.888 ms, ainda acima. O ajuste é de implementação (limitar candidatos do ranking), a decidir fora desta PR, e o alvo só muda neste ADR com o valor medido depois dele. Os alvos de frescor, alerta, outbox e disponibilidade seguem não medidos.

## Prova

- `make lint-observability`: lint dos dashboards e promtool em `infra/prometheus/`. O teste de regras é `infra/prometheus/rules/slo_test.yml`, que falha se o alerta de queima rápida de disponibilidade deixar de disparar com 10% de erro 5xx, ou se disparar com tráfego saudável.
- Commit de teste (RED) da regra: `test(obs): slo rule unit tests and observability lint`.

### Medido na PR 6.3 (`make bench`, 1,2 milhão de linhas, inclui dados sintéticos, 1.198.367 linhas)

Mesma massa, máquina e carga da PR 2.7, depois de limitar o ranking da busca textual e de ter a tabela de órgãos (ADR-0011, "Perfil e otimização"). Uma execução por cenário, `docs/benchmarks/search/2026-10-23-pr63/README.md`:

| Cenário | Usuários | p50 | p95 | p99 | Erros | Alvo |
|---|---|---|---|---|---|---|
| S1 busca textual | 50 | 34 ms | 83 ms | 122 ms | 0 de 8.662 | **atende** (p95 200 ms, p99 500 ms) |
| S2 busca com UF, modalidade e `open=true` | 50 | 6 ms | 22 ms | 46 ms | 0 | atende |
| S4 órgão com erro de digitação | 20 | 13 ms | 18 ms | 23 ms | 0 de 3.545 | sem alvo no plano (era 544 erros em 720) |

O alvo do S1 continua o do plano: a medição passou a confirmá-lo, e ele não foi alterado em nenhum momento. O S1 melhorou de p95 3.402 ms para 83 ms. O custo é de relevância, não de latência: o ranking vale para os 2.000 candidatos mais recentes (ADR-0011 mede 6,4 de 20 resultados do top 20 exato mantidos nesta massa). Uma rodada não é série: a medição segue sendo uma execução na máquina do desenvolvedor, com ruído de ambiente.
