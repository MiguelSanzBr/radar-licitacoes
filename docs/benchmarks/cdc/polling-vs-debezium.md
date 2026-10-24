# Comparação: relay por polling e Debezium (CDC)

Rótulo: **dados de teste sintéticos**. Os eventos do outbox são gerados pelo benchmark, nenhum dado real do PNCP entra na medição.

Data: 2026-10-23

Commit: `cfa440e` (squash do PR 8.3; o benchmark roda o `PollingVsDebeziumBenchIT` desse commit).

Máquina: AMD Ryzen 5 5600GT, 12 threads, 11,5 GiB de RAM, Linux 6.12, Docker 29.9.2. Postgres `postgres:18` (18.6, `wal_level=logical`, `shared_buffers` padrão), Kafka `apache/kafka:4.1.0` (um nó, KRaft) e `quay.io/debezium/connect:3.6.3.Final` (Kafka Connect 4.3 em modo standalone), um contêiner de cada, no mesmo host da JVM do teste. A máquina não estava ociosa: outros builds e o ambiente gráfico rodavam em paralelo (carga média entre 5,8 e 10 em 12 threads na execução abaixo). Rodei duas vezes: a primeira, com a coluna `envelope` presente também no polling, deu p50 de 709 ms (polling) e 492 ms (cdc) a 100 eventos/s e o mesmo WAL do polling (25,9 MB); a segunda, a que está nas tabelas, mede o polling sem a coluna (ela é opt-in) e o cdc com ela. As duas concordam dentro de cerca de 40 ms no p50, então a diferença entre os modos não vem do ruído nem da coluna. Dentro de uma execução há um só ponto por cenário.

Comando:

```
./mvnw -Pbench verify -pl core -am -Dtest=NoSuch -Dit.test=PollingVsDebeziumBenchIT -Dsurefire.failIfNoSpecifiedTests=false
```

## Cenário

`PollingVsDebeziumBenchIT` grava 10 000 eventos de `ProcurementUpdated` (payload de cerca de 1,6 KB) pelo `OutboxWriter` real, em transações de 5 eventos (100 no cenário de rajada), sobre a mesma tabela `outbox.outbox_event`. Cada modo roda três cenários, depois de uma rodada de aquecimento de 500 eventos que não entra nos números:

- `steady-100`: 100 eventos/s, 100 s. É uma carga que o relay sustenta.
- `steady-250`: 250 eventos/s, 40 s. É mais do que o relay publica.
- `burst`: os 10 000 de uma vez, sem pausa.

Os dois modos nunca rodam juntos: o relay (o mesmo `OutboxScheduler`, a cada 500 ms, com lotes de 100) roda primeiro, sem a coluna `envelope`, e para antes de o teste aplicar `infra/cdc/enable-cdc.sql` e subir o conector; o conector sobe com o slot ainda inexistente e o relay não é chamado.

Medidas:

- Latência: do `created_at` da linha (início da transação) até o horário de append no broker. Os tópicos são trocados para `message.timestamp.type=LogAppendTime`, então o carimbo é do broker e não do produtor (o Debezium carimba com o horário de commit da origem). Relógio único, é a mesma máquina.
- CPU do Postgres: tempo de CPU do cgroup do contêiner (`cpu.stat`, `usage_usec`) entre o início e o fim da rodada, mais média e pico de `docker stats` amostrado a cada cerca de 1 s. O Kafka e o conector ganham a mesma leitura de cgroup.
- WAL: `pg_wal_lsn_diff` entre o início e o fim da rodada.

## Resultados

Latência (ms) de 10 000 eventos por linha:

| Modo | Cenário | p50 | p95 | máx | Esvaziar depois do último insert |
|---|---|---|---|---|---|
| polling | `steady-100` | 747 | 1 048 | 1 266 | 0,5 s |
| cdc | `steady-100` | 501 | 848 | 1 001 | 0,6 s |
| polling | `steady-250` | 10 000 | 18 419 | 19 290 | 19,3 s |
| cdc | `steady-250` | 487 | 846 | 1 334 | 0,5 s |
| polling | `burst` | 28 209 | 55 228 | 58 390 | 58,4 s |
| cdc | `burst` | 558 | 1 003 | 1 110 | 0,7 s |

Custo no mesmo período:

| Modo | Cenário | WAL gerado | CPU do Postgres (s) | CPU média / pico (`docker stats`) | CPU do Kafka (s) | CPU do conector (s) |
|---|---|---|---|---|---|---|
| polling | `steady-100` | 25,9 MB | 2,53 | 2,6% / 10,9% | 12,3 | 0 |
| cdc | `steady-100` | 15,0 MB | 2,96 | 2,5% / 3,4% | 2,9 | 5,0 |
| polling | `steady-250` | 25,4 MB | 2,68 | 4,6% / 33,5% | 7,7 | 0 |
| cdc | `steady-250` | 11,9 MB | 3,15 | 6,4% / 8,3% | 0,8 | 1,7 |
| polling | `burst` | 25,0 MB | 1,65 | 3,5% / 56,1% | 9,1 | 0 |
| cdc | `burst` | 11,6 MB | 1,43 | 21,1% / 63,0% | 1,0 | 1,5 |

Complexidade operacional:

| | polling | cdc |
|---|---|---|
| Componentes no caminho do evento | 3 (app, Postgres, Kafka) | 4 (app, Postgres, Kafka, Kafka Connect com o conector), mais um serviço de uma execução só (`cdc-setup`) no compose |
| Imagem extra / memória do componente extra | nenhuma | 2,4 GB de imagem, de 348 MiB (ocioso) a 464 MiB (depois do seed de 1 633 eventos); limite do compose em 640 MiB |
| Objetos de banco além da tabela | nenhum (o CDC é opt-in e o Flyway não cria nada dele) | `enable-cdc.sql`: coluna `envelope`, função `build_envelope`, tabela `cdc_heartbeat`, publicação `radar_outbox`; mais o slot `radar_outbox` |
| Custo do polling pelo CDC existir | zero | n/a |
| Configuração do Postgres | padrão | PostgreSQL 18 e `wal_level=logical` (exige reinício); `max_slot_wal_keep_size` recomendado |
| Passos do runbook para ligar | 0 (é o padrão) | 7 (ou `PROFILE=cdc make up` num ambiente novo) |
| Passos do runbook para voltar | 0 | 4 |
| Alertas próprios | `OutboxOldestAgeHigh` | `OutboxReplicationSlotLagHigh` e `OutboxReplicationSlotInactive` (`OutboxOldestAgeHigh` fica sem sinal) |
| Falha típica | broker fora: o relay espera e retoma | conector parado: o slot retém WAL no disco até o conector voltar, o slot ser apagado ou o Postgres invalidá-lo ao passar de `max_slot_wal_keep_size` |

Os passos estão em [Trocar o modo do outbox](../../runbook.md#trocar-o-modo-do-outbox) e em [Slot do CDC atrasado](../../runbook.md#slot-do-cdc-atrasado).

## O que os números mostram

- O relay publica uma mensagem por vez e espera o ack (`acks=all`, produtor idempotente). Nesta máquina isso dá cerca de 170 eventos/s (a rajada de 10 000 levou 58 s, nas duas execuções). Abaixo disso a latência é a do intervalo de polling (500 ms) mais o envio (p50 de 709 e 747 ms a 100/s nas duas execuções); acima disso a fila cresce e a latência é tempo de espera (p50 de 9,8 e 10,0 s a 250/s).
- O Debezium manteve p50 de cerca de 490 a 560 ms e p95 abaixo de 1,0 s nos três cenários, sem fila. O piso de cerca de 500 ms é o `poll.interval.ms` padrão do conector (500 ms); reduzi-lo baixaria a latência, mas não foi medido.
- A 100 eventos/s o CDC ganha cerca de 250 ms no p50 (709 e 747 ms contra 492 e 501 ms nas duas execuções) e cerca de 200 ms no p95; ambos ficam abaixo de 1,1 s no p95, então é uma diferença pequena e estável, não decisiva. A diferença grande aparece quando a carga passa da capacidade do relay.
- O CDC gera de 40% a 60% do WAL do polling: o relay faz um `UPDATE` por linha (`published_at`), que reescreve a tupla e o índice parcial; o CDC só insere. A coluna `envelope` não distorce isso: o polling gerou 25,9 MB com a coluna (primeira execução) e 25,9 MB sem ela.
- A CPU do Postgres ficou na mesma ordem nos dois modos (1,4 a 3,2 s por 10 000 eventos): a decodificação lógica custa um pouco mais em carga contínua (3,0 contra 2,5 s a 100/s) e menos ou igual na rajada (1,4 contra 1,7 s). A CPU total do sistema muda de lugar: o relay gasta no Kafka (de 8 a 12 s por 10 000 eventos, uma requisição de produção por mensagem; a CPU do JVM da aplicação não foi medida); o conector manda lotes e gasta de 1,5 a 5 s no próprio processo e de 0,8 a 2,9 s no Kafka.
- O `steady-100` do conector (5,0 s) inclui ociosidade e o aquecimento do JIT durante 100 s; o `steady-250` (1,7 s) mostra o custo com o conector quente.

Limites desta medição: máquina com carga alheia (carga média de até 10), um ponto por cenário em cada execução, `created_at` é o início da transação (nas rajadas de 100 eventos o commit sai alguns milissegundos depois), payload de um só tipo de evento e um relay sem envio em lote. O limite de 170 eventos/s é do relay atual (envio síncrono por mensagem) e não do polling em si: um envio assíncrono em lote o elevaria, mas não está implementado.

## Teste do batimento do slot

Medido no compose, com o outbox parado e outra tabela recebendo 60 MB de escrita: só com `heartbeat.interval.ms=10000`, o lag do slot ficou em 64 MB por mais de 30 s (o servidor só envia o que está na publicação, e o conector confirma o LSN ao processar evento). Com `heartbeat.action.query` escrevendo em `outbox.cdc_heartbeat` (que está na publicação), o lag caiu de 61 MB para 112 bytes em cerca de 16 s e voltou a poucos kB em seguida.

## Campos que o CDC não reproduz igual ao relay

O valor e a chave saem iguais, campo a campo (o `CdcEquivalenceIT` passa os dois modos pelas mesmas asserções). Diferenças que sobram, todas sem efeito nos consumidores:

- O JSON do valor sai normalizado pelo `jsonb` (ordem das chaves e espaço depois de `:` e `,`); o relay escreve na ordem de inserção e sem espaços. A árvore é a mesma.
- O cabeçalho `traceparent` não é emitido. O relay grava um cabeçalho que é um span filho do trace guardado; o Debezium não cria spans. O `traceparent` guardado continua no corpo do envelope (`traceparent`) e os consumidores já usam o corpo quando o cabeçalho falta. Os outros três cabeçalhos (`eventId`, `eventType`, `schemaVersion`) saem iguais, em texto puro.
- O envelope só traz `occurredAt` quando o cabeçalho `occurredAt` da linha existe; o relay cai para `created_at` quando falta. O `OutboxWriter` sempre grava o cabeçalho, então a diferença só vale para linhas inseridas à mão.
- O carimbo de tempo do registro Kafka é o horário de commit da origem (Debezium) e não o do envio (relay).
