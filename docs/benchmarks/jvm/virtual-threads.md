# Threads virtuais na coleta: plataforma × virtual

Data: 2026-10-22. Commit: `9743642` (código de produção em `4a35a5b`). JDK 25.0.5+8. Máquina: AMD Ryzen 5 5600GT (12 threads), 11 GiB de RAM, Linux 6.12.

## Como medir

```
./mvnw -Pbench test -pl core -am -Dtest=VirtualThreadsBenchTest
```

O teste (`@Tag("bench")`, fora do `verify`) sobe um WireMock que responde 200 páginas, cada uma com 200 ms de latência, e lê as 200 páginas em ordem pelo `PncpHttpGateway` de verdade (retry, limitador, bulkhead e disjuntor ligados). A taxa do limitador está em 100 mil req/s **só aqui**, para isolar o efeito das threads: no cliente real o limitador é o teto (padrão 0,2 req/s, ADR-0017). Uma leitura de aquecimento antecede a medição. "Threads extras" é o pico de threads de plataforma da JVM durante a leitura menos as vivas antes dela (`ThreadMXBean`); threads virtuais não entram nessa conta.

## Resultado

| estratégia | bulkhead | tempo (ms) | threads extras no pico |
|---|---|---|---|
| sequencial (hoje) | - | 41354 | 0 |
| pool de plataforma = bulkhead | 4 | 10557 | 4 |
| virtual, adiantamento = bulkhead | 4 | 10556 | 0 |
| pool de plataforma de 200 | 4 | 10564 | 200 |
| virtual, adiantamento = 200 | 4 | 10572 | 0 |
| pool de plataforma = bulkhead | 16 | 3042 | 16 |
| virtual, adiantamento = bulkhead | 16 | 3004 | 0 |
| pool de plataforma de 200 | 16 | 2950 | 200 |
| virtual, adiantamento = 200 | 16 | 2886 | 0 |

Uma execução por linha, sem repetição nem desvio padrão (o teste afirma só que toda linha concorrente bate a sequencial e que as virtuais não precisam de pool): as diferenças de poucos por cento entre linhas de mesmo bulkhead não permitem concluir nada sobre o tipo de thread.

## Conclusão

- O ganho de tempo vem do bulkhead, não do tipo de thread: 200 × 200 ms em sequência levam 41 s; com 4 chamadas simultâneas, 10,6 s (3,9 vezes); com 16, 3,0 s (13,8 vezes). O tipo de thread não mudou o tempo em nenhuma linha.
- Threads virtuais dispensam dimensionar o pool: com bulkhead 4, 200 tarefas esperando vaga custam 0 threads de plataforma extras, contra 200 de um pool de plataforma do mesmo tamanho (que só serve para ficar bloqueado esperando o bulkhead) e 4 de um pool bem ajustado ao bulkhead. Em produção a profundidade da leitura adiantada é `radar.ingest.fetch-ahead` (padrão 2, no máximo o bulkhead); a tabela usa o bulkhead inteiro só para medir o efeito das threads.
- Isto vale para latência. O PNCP real limita por taxa (429 a 0,5 req/s sustentados), e o limitador continua o teto: a 0,2 req/s o backfill leva 5 s por página com qualquer concorrência, e o paralelismo só passa a pesar se a sonda liberar uma taxa em que 1/taxa fique abaixo da latência observada (3 a 10 s nas respostas lentas).

## Pinning (JFR)

```
./mvnw -Pjfr verify -pl core -am -Dit.test=BackfillIT
```

O perfil `jfr` liga `-XX:StartFlightRecording=name=pinning,...,jdk.VirtualThreadPinned#threshold=20ms` no failsafe (e no surefire) (com `stackdepth=256`; um rastro ainda assim truncado conta como passando pelo código de `ingest`, para não esconder um pino) e o teste `noVirtualThreadIsPinnedForLongOnTheIngestionPath` lê o snapshot da gravação com `RecordingFile` e falha se um pino de 20 ms ou mais passar por código de `br.dev.miguelsanzbr.radar.ingest`. Sem `-Pjfr` o teste grava só o próprio trabalho.

- `BackfillIT`: 0 eventos `jdk.VirtualThreadPinned` de 20 ms ou mais.
- Rodada ampla sob JFR (`BackfillIT`, `IncrementalSyncIT`, `ProcurementEventsForAlertsIT`, `ProjectionIdempotencyIT`, `OutboxRelayIT`, 39 s): 0 eventos, incluindo os ouvintes Kafka de alertas, que rodam em threads virtuais.
- O detector funciona: o teste `PinningProbeTest` bloqueia 80 ms num inicializador de classe dentro de uma thread virtual e o pino aparece (também sob `-Pjfr`, onde o `pinning-unit.jfr` registra 1 evento).
- No JDK 25 (JEP 491) `synchronized` já não prende a thread virtual; o que ainda prende é inicialização de classe e frames nativos. A regra de ArchUnit contra `synchronized` no caminho de I/O é preventiva.
