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

`OutboxConcurrencyIT` insere 10 000 eventos em 500 agregados, sobe duas threads cada uma com seu `OutboxRelay` e seu publicador em memória, e cada thread chama `relayOnce()` em laço até não haver pendentes. Cada chamada é um ciclo. Cada ciclo também é verificado: no máximo 100 mensagens, em ordem de id. O teste exige união dos ids publicados igual a 10 000, nenhum id publicado duas vezes e ciclos disjuntos entre os relays.

Linha impressa pelo teste (latência de 1 ms por publicação, simulando o ack do broker):

```
OUTBOX_CONCURRENCY events=10000 relayA=5000 relayB=5000 cyclesA=50 cyclesB=50 duplicates=0 timeMs=5964
```

| Variante | Eventos | Relay A | Relay B | Duplicados | Tempo |
|---|---|---|---|---|---|
| Latência de 1 ms por publicação | 10 000 | 5 000 | 5 000 | 0 | 6,0 s |
| Sem latência (publicador instantâneo) | 10 000 | 5 000 | 5 000 | 0 | 0,7 s |

O limite do teste é 60 s.

## Leitura

- Duplicados em um mesmo ciclo são impossíveis por construção: `FOR UPDATE SKIP LOCKED` faz o segundo relay pular as linhas travadas pelo primeiro, e o `published_at` é gravado na mesma transação que as trava. Depois do commit as linhas já não casam com `published_at IS NULL`. O número 0 acima confirma isso, mas o que protege é o desenho, não a sorte.
- A entrega continua sendo ao menos uma vez: se o processo cair entre o ack do broker e o commit, o lote é publicado de novo. O consumidor é idempotente pelo Inbox.
- A ordem por agregado vale dentro de um lote e com um relay ativo. Com dois relays a ordem entre lotes não é garantida (ver ADR-0018).
