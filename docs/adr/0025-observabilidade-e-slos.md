# ADR-0025: Observabilidade com Micrometer e LGTM, e SLOs com burn rate

- Status: proposta
- Data: 2026-10-20

## Contexto

O radar coleta dados de uma API externa (PNCP), move eventos pelo outbox e pelo Kafka, projeta leituras para a busca e envia alertas por e-mail. Uma falha em qualquer etapa só aparece para quem usa a busca, muitas vezes horas depois. Precisamos medir o frescor dos dados, a latência e a disponibilidade da busca e a entrega dos alertas, e saber quando o orçamento de erro está sendo consumido rápido demais.

Os alvos desta decisão vêm da seção 7.4 do plano. Nenhum deles foi medido ainda.

## Decisão

- Métricas com Micrometer, expostas em `/actuator/prometheus`. O tracing com a ponte OpenTelemetry e o envio OTLP para o stack LGTM ficam na tarefa 4.1.
- Prometheus faz scrape do app a cada 15 s (`infra/prometheus/prometheus.yml`).
- Quatro dashboards versionados em `infra/grafana/dashboards/` (coleta, outbox e Kafka, busca e API, alertas). Cada painel lê o datasource pela variável `datasource`, sem UID fixo.
- Regras de SLO em `infra/prometheus/rules/slo.yml`: burn rate com duas janelas. Alerta crítico com 14,4 vezes o ritmo aceito em 1 h e 5 min (consome cerca de 2% do orçamento de 30 dias em uma hora). Alerta de aviso com 6 vezes em 6 h e 30 min (cerca de 5% do orçamento). Alertas de limiar simples para outbox, projeção e entrega de alerta, com `for` de 10 min.
- Os alertas têm `severity`, `summary` e `runbook_url` apontando para `docs/runbook.md`.
- Dois alertas de saúde do próprio scrape: `RadarTargetDown` (`up{job="radar"} == 0` por 2 min, crítico) e `RadarSemTrafego` (nenhuma requisição à busca por 15 min, aviso, com `absent()`).
- Testes das regras com `promtool test rules` (`infra/prometheus/rules/slo_test.yml`): cada alerta dispara com séries sintéticas que violam o alvo, e nenhum dispara com tráfego saudável.

Alvos propostos, não medidos:

| SLO | Alvo proposto | Situação |
|---|---|---|
| Frescor interno: `fetched_at` até `projected_at` | p95 até 30 s | proposto, não medido |
| Frescor ponta a ponta: `dataAtualizacaoGlobal` até visível na busca | p95 até o intervalo de polling mais 1 min | proposto, não medido (medido em `make slo`) |
| Alerta: `projected_at` até o SMTP aceitar | p95 até 60 s | proposto, não medido |
| Busca S1 e S2 com 1,2 milhão de linhas | p95 até 200 ms, p99 até 500 ms | proposto, não medido (Gatling em `make slo`) |
| Detalhe S5 com cache | p95 até 50 ms | proposto, não medido (Gatling em `make slo`) |
| Outbox | `outbox_oldest_age_seconds` p99 até 5 s | proposto, não medido |
| Disponibilidade da API durante caos com Toxiproxy | 99,5% em 1 h | proposto, não medido (Gatling em `make slo`) |

Duas conversões feitas nas regras:

- Disponibilidade da busca: 99,5% de respostas sem 5xx em `/api/v1/procurements`.
- Latência da busca: "p95 até 200 ms" vira "pelo menos 95% das respostas abaixo de 200 ms". As duas formas são equivalentes, mas a segunda é o que o histograma com bucket `le="0.2"` mede diretamente. O p99 de 500 ms não tem alerta de burn rate; aparece no painel de busca.

A configuração de buckets fica em `core/src/main/resources/application.properties`: `management.metrics.distribution.slo.http.server.requests=50ms,200ms,500ms`, com `percentiles-histogram` ligado para o `histogram_quantile` do painel. A projeção e a entrega de alerta usam histogramas que entram na tarefa 4.1, com a mesma configuração de distribuição.

Regra para os alvos: alvo que a medição não confirmar é ajustado no plano e registrado neste ADR com o valor medido. Nunca o contrário, ou seja, não se ajusta a medição para caber no alvo.

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

## Prova

- `make lint-observability`: lint dos dashboards e promtool em `infra/prometheus/`. O teste de regras é `infra/prometheus/rules/slo_test.yml`, que falha se o alerta de queima rápida de disponibilidade deixar de disparar com 10% de erro 5xx, ou se disparar com tráfego saudável.
- Commit de teste (RED) da regra: `test(obs): slo rule unit tests and observability lint`.
