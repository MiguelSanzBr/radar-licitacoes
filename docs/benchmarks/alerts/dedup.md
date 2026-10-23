# Prova: matcher de alertas e deduplicação

Data: 2026-10-22

Máquina: AMD Ryzen 5 5600GT with Radeon Graphics, 12 threads, 11,5 GiB de RAM, com outros processos em uso (`lscpu | grep 'Model name'`, `/proc/meminfo`). Postgres 18 (`postgres:18`) e Kafka `apache/kafka:4.1.0` (um nó, KRaft) via Testcontainers, um contêiner de cada por JVM.

Comandos:

```
./mvnw -pl core verify -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='MatchProcurementUseCaseIT,JdbcReverseSearchIT,ProcurementEventsForAlertsIT,MatchBenchmarkIT'
```

## Cenários de deduplicação

O caminho real: o `OutboxAppender` do ingest grava os eventos de licitação, o relay publica em `radar.procurement.v1`, o ouvinte `alerts-matcher` (grupo próprio, `auto.offset.reset=latest`) casa, e o relay publica o `AlertMatched` em `radar.alert.v1`. Cada teste espera o grupo ter as 12 partições atribuídas, começa com a defasagem do tópico zerada e termina do mesmo modo, e usa eventos com `occurredAt` de agora (o matcher descarta eventos com mais de `radar.alerts.max-event-age`, padrão `P1D`).

| # | Cenário | Teste | Resultado |
|---|---|---|---|
| 1 | `ProcurementDiscovered` que casa 1 busca | `ProcurementEventsForAlertsIT` e `MatchProcurementUseCaseIT` | 1 linha em `alert_delivery`, 1 `AlertMatched` no outbox (agregado `Alert`, chave = `subscriberId`), válido contra `alert-matched.v1.schema.json` e o envelope |
| 2 | `ProcurementUpdated` do mesmo edital com o texto mudado | os dois | 0 linha nova em `alert_delivery`, 0 `AlertMatched` novo (a chave primária deduplica); o evento entra no inbox |
| 3 | O mesmo `ProcurementDiscovered` entregue 3 vezes | os dois | 1 linha em `alert_delivery`, 1 em `processed_event`, 1 `AlertMatched` |
| 4 | `ProcurementUpdated` com `changedFields` só de situação (ou `pncpUpdatedAt`, `srp` etc.) | os dois | nenhuma consulta: `processed_event` e `alert_delivery` não mudam; `modality` passa a disparar o matcher (teste parametrizado do caso de uso) |
| 5 | Filtros: UF e IBGE diferentes, faixa de valor (limites inclusivos, valor ausente nunca satisfaz), modalidade, `open=true` com fim de proposta no passado, vazio ou igual a agora | `JdbcReverseSearchIT` | casa só quem passa em todos os filtros |
| 6 | Assinante apagado, assinante não verificado, busca inativa | `JdbcReverseSearchIT` | 0 casamento |
| 7 | Mensagem que não é JSON no mesmo tópico e chave, antes de um evento válido | `ProcurementEventsForAlertsIT` | descartada sem nova tentativa, o evento seguinte é casado |
| 8 | Falha transitória no casamento | `ProcurementEventsForAlertsIT` | o evento é tentado de novo e casado uma vez (`processed_event` com 1 linha) |
| 9 | Queda do banco (`DataAccessException`) por mais vezes que as tentativas limitadas | `ProcurementEventsForAlertsIT` | tentado sem limite, e casado uma vez quando o banco volta |
| 10 | Evento com `occurredAt` de 2 dias atrás | `ProcurementEventsForAlertsIT` | reconhecido sem trabalho: nada no inbox; o evento novo da mesma chave é casado |
| 11 | Falha ao gravar o segundo `AlertMatched` (duas buscas casam) | `MatchProcurementUseCaseIT` | rollback completo, inclusive do primeiro alerta já gravado |

Outros comportamentos da consulta, também em `JdbcReverseSearchIT`: acento e caixa ignorados como no lado da busca, órgão, unidade e município pesquisáveis, sintaxe de `websearch_to_tsquery` (negação, `or`, frase), `query_text` vazio ou só com espaços como busca só por filtros, busca só de stopwords nunca casa, filtros guardados como `null` não restringem.

## Tempo do matching contra 10 000 buscas

`MatchBenchmarkIT` insere 1 000 assinantes verificados e 10 000 buscas ativas (uma ou duas palavras de um vocabulário de cerca de 100, um terço com filtro de UF, 20% diárias) e casa 50 licitações. Cada licitação tem o mesmo texto e um número de controle diferente, e 78 buscas casam. O evento inteiro grava as 78 linhas de entrega e os 78 `AlertMatched`, o pior caso por evento; a consulta sozinha é medida à parte.

Linha impressa pelo teste (última execução completa):

```
ALERTS-BENCH searches=10000 matchesPerEvent=78 runs=50 queryP50=24.0 ms queryMax=34.2 ms fullFlowP50=61.9 ms fullFlowMax=153.1 ms
```

| Medida | p50 de 50 execuções | Máximo |
|---|---|---|
| Consulta de busca reversa (`ReverseSearch.find`) | 24,0 ms | 34,2 ms |
| Evento inteiro (inbox, consulta, 78 entregas, 78 `AlertMatched`, commit) | 61,9 ms | 153,1 ms |

Em sete execuções do teste no mesmo dia, o p50 da consulta ficou entre 21,7 e 31,6 ms e o do evento inteiro entre 49,8 e 93,1 ms. O custo é linear no número de buscas (cerca de 2,4 µs por busca na consulta). O teste só exige p50 abaixo de 5 s, como trava de sanidade, não como promessa. A decisão e o limite para trocar de estratégia estão no ADR-0015.
