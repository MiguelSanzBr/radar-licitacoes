# Prova: atomicidade do outbox e relays concorrentes

Data: 2026-10-21

Máquina: AMD Ryzen 5 5600GT with Radeon Graphics, 12 threads, 11,5 GiB de RAM (`lscpu | grep 'Model name'`, `/proc/meminfo`). Postgres 18 via Testcontainers, um contêiner por JVM.

Comando:

```
./mvnw -pl core verify -Dit.test='JdbcOutboxAppenderIT,OutboxRelayIT,OutboxConcurrencyIT,OutboxCleanupIT' -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
```

## Cenários

| Cenário | Teste | Resultado |
|---|---|---|
| Exceção depois de `save` e `append`, antes do commit | `JdbcOutboxAppenderIT.exceptionAfterSaveAndAppendLeavesNothingInEitherTable` | 0 linhas em `ingest.procurement` e 0 em `outbox.outbox_event` |
| Mesmo bloco, sem a exceção | `commitKeepsOneRowInEachTable` | 1 linha em cada tabela |
| `append` fora de transação | `appendOutsideATransactionIsRejected` | `IllegalTransactionStateException` (propagação `MANDATORY`), nenhuma linha gravada |
| Falha de um agregado no lote | `OutboxRelayIT.failureBlocksLaterEventsOfThatAggregateOnlyAndRecordsTheError` | os eventos seguintes do agregado ficam não publicados, os outros agregados seguem; `attempts` e `last_error` atualizados; na rodada seguinte a ordem `A1, A2` é mantida |
| Poison | `poisonAfterMaxAttemptsCountsOnceAndStopsBlocking` | com 10 falhas `outbox_poison_total` sobe 1 vez, a linha sai da consulta (`attempts < 10`) e o publicador não é mais chamado para ela |
| Exceção de runtime do publicador | `runtimeExceptionFromThePublisherCountsAsAnAttemptAndIsolatesTheAggregate` | conta tentativa, `last_error` = `e.toString()`, o agregado é isolado e os outros seguem |
| Rollback do lote com poison | `poisonCounterIsNotIncrementedWhenTheBatchTransactionRollsBack` | `outbox_poison_total` só sobe depois do commit |
| Gauges | `gaugesReportPendingCountAndOldestAge`, `poisonRowsLeaveTheOldestAgeAndShowUpInTheirOwnGauge` | linhas no limite de tentativas saem de `outbox_oldest_age_seconds` e aparecem em `outbox_poison_pending` |
| Limpeza | `OutboxCleanupIT` | só publicados com mais de 7 dias saem, não publicados ficam mesmo com 1000 dias (§10 item 10) |

## Dois relays concorrentes sobre 10 000 eventos

`OutboxConcurrencyIT` insere 10 000 eventos em 50 agregados, sobe duas threads, cada uma com seu `OutboxRelay`, e as duas publicam no mesmo `InMemoryEventPublisher`, de modo que a ordem do registro é a ordem que um consumidor veria. Cada thread chama `relayOnce()` em laço até não haver pendentes. Cada chamada é um ciclo. O teste exige: 10 000 ids publicados, nenhum repetido, ids de cada agregado em ordem crescente, pelo menos um ciclo que devolveu 0 porque a trava estava com o outro relay, nada pendente no fim e cada ciclo com no máximo 100 mensagens.

Linha impressa pelo teste (latência de 1 ms por publicação, simulando o ack do broker):

```
OUTBOX_CONCURRENCY events=10000 aggregates=50 published=10000 distinct=10000 cyclesA=6140 cyclesB=5081 skippedCycles=11121 timeMs=13570
```

| Variante | Eventos | Duplicados | Ciclos sem trava (devolveram 0) | Tempo |
|---|---|---|---|---|
| Latência de 1 ms por publicação | 10 000 | 0 | 11 121 | 13,6 s |
| Sem latência (publicador instantâneo) | 10 000 | 0 | 322 | 1,6 s |

Antes da trava (relays em paralelo com `SKIP LOCKED`) o mesmo cenário levava 6,0 s e 0,7 s, com 500 agregados e cada relay publicando metade dos eventos, mas sem garantia de ordem. Agora só um relay trabalha por vez e o outro gira em vazio, daí o tempo maior e o grande número de ciclos que devolvem 0 (cada um é uma transação curta que só tenta a trava; no scheduler real isso acontece uma vez por intervalo de 500 ms, não em laço). O limite do teste é 60 s.

## Trava do relay

`OutboxRelayIT` cobre a trava `pg_try_advisory_xact_lock` em duas situações:

| Teste | Resultado |
|---|---|
| `aRelayHoldingTheLockMakesASecondRelayReturnZeroUntilItCommits` | com 101 linhas pendentes (uma além do lote), um segundo relay chamado durante o lote do primeiro devolve 0 e não publica nada; depois do commit do primeiro, o segundo publica a linha restante |
| `theLockIsReleasedWhenTheBatchTransactionRollsBack` | um lote que morre com `Error` desfaz a transação e um relay seguinte volta a publicar, ou seja, a trava não vaza |

## Leitura

- A ordem por agregado vale entre lotes e entre relays: a trava deixa um relay ativo por vez, e ele lê as pendentes por `id`. Ver ADR-0018.
- Duplicados em um mesmo ciclo continuam impossíveis por construção: a trava serializa os relays e, como segunda proteção, `FOR UPDATE SKIP LOCKED` faz qualquer trabalhador manual pular linhas já travadas. O `published_at` é gravado na mesma transação que as trava.
- A entrega continua sendo ao menos uma vez: se o processo cair entre o ack do broker e o commit, o lote é publicado de novo. O consumidor é idempotente pelo Inbox.
- O custo é a vazão de um relay só. Se não bastar, a alternativa é particionar os relays por `hash(aggregate_id) mod N` (ADR-0018) ou trocar o polling por CDC (PR 8.3).
