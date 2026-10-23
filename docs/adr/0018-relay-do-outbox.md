# ADR-0018: relay do outbox por polling, serializado por advisory lock

- Status: aceita; atualizada em 2026-10-23 com a comparação medida no PR 8.3
- Data: 2026-10-21

## Contexto

O contexto `ingest` grava o agregado e o evento na mesma transação (tabela `outbox.outbox_event`). Alguém precisa levar as linhas até o Kafka sem perder evento e sem travar a coleta. As opções são ler a tabela por polling, acordar o relay com `LISTEN/NOTIFY` ou ler o WAL com CDC (Debezium).

## Decisão

Polling com `SELECT ... WHERE published_at IS NULL AND attempts < 10 ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`, em uma transação por lote, a cada 500 ms (e em laço enquanto os lotes vierem cheios).

- A primeira instrução da transação é `SELECT pg_try_advisory_xact_lock(hashtextextended('radar.outbox.relay', 0))`. Se outro relay já tem a trava, `relayOnce()` devolve 0 na hora. A chave é o hash de uma string fixa, a trava vale até o commit ou rollback da transação e some sozinha se o processo cair. `FOR UPDATE SKIP LOCKED` continua na consulta: não custa nada e protege contra um trabalhador manual fora do relay.
- Publicação síncrona, em ordem de `id`, e só depois `published_at = now()` no mesmo commit que libera as travas.
- Falha em uma mensagem: `attempts + 1` e `last_error` (até 2000 caracteres); as mensagens seguintes do mesmo `aggregate_id` no lote são puladas, os outros agregados continuam.
- Com 10 tentativas a linha sai da consulta, a métrica `outbox_poison_total` sobe e o resto da fila não é bloqueado. A linha fica na tabela para investigação.
- Limpeza diária de publicados com mais de 7 dias, em lotes de 10 000, sempre com `published_at IS NOT NULL`.
- O módulo `outbox` é genérico (não importa nada de `ingest`) para o contexto `alerts` reutilizar.

## Garantias e limites

- Entrega ao menos uma vez. Se o processo cair entre o ack do broker e o commit, o lote é publicado de novo. Os consumidores são idempotentes pelo Inbox (`processed_event`).
- A ordem por agregado é garantida entre lotes e entre relays: só um relay publica por vez, sempre as linhas pendentes mais antigas primeiro. Sem a trava, `SKIP LOCKED` deixava o relay B publicar a linha 101 de um agregado enquanto o relay A ainda segurava a 100, e o consumidor via os eventos fora de ordem (o projetor de busca adiava um `SituationChanged` ou `Closed` cuja linha ainda não existia, ou descartava um `Updated` após um `SituationChanged` da mesma versão).
- Custo: há um relay ativo por vez, então a vazão é a de um relay (lote de 100, uma publicação síncrona por mensagem). Um segundo relay serve para continuidade: se o primeiro cair, a trava é liberada e o segundo assume no próximo ciclo. Ele não drena backlog em paralelo, e um relay lento segura os demais enquanto durar a transação.
- Depois do poison, um evento posterior do mesmo agregado deixa de esperar pelo evento envenenado e pode ser publicado antes dele. É a consequência de não bloquear a fila; a guarda de versão do consumidor cobre.
- Falha de uma mensagem é `PublishException` ou qualquer `RuntimeException` do publicador: as duas contam tentativa, gravam `last_error` (`e.toString()`, até 2000 caracteres) e isolam o agregado no lote. Só um `Error` aborta o lote, e a transação desfaz tudo. `outbox_poison_total` é incrementado depois do commit, então um rollback e nova tentativa não contam duas vezes.
- Falha do broker inteiro (`BrokerUnavailableException`: sem ack no prazo ou erro retentável do Kafka) encerra o lote na primeira mensagem, para não gastar um timeout por agregado. Grava `last_error`, mas não conta tentativa nem toca `outbox_poison_total`: uma queda do broker não é culpa da mensagem e não pode envenenar a fila. Erros específicos da mensagem continuam contando e isolando só o agregado.
- A transação do relay fica aberta (com as travas das linhas) enquanto o publicador espera o broker, então o tempo do lote é limitado pelo timeout do publicador: no PR 1.7 o produtor Kafka define `delivery.timeout.ms` e `max.block.ms` curtos. Numa queda geral do broker o lote deve abortar cedo, em vez de esgotar o timeout mensagem a mensagem e contar tentativa de todas; o PR 1.7 implementa isso.
- Métricas: `outbox_pending` (não publicados), `outbox_oldest_age_seconds` (a mais antiga ainda tentável, sem as linhas no limite de tentativas) e `outbox_poison_pending` (não publicados no limite de tentativas, a vigiar por alerta).

## Alternativas

- `LISTEN/NOTIFY`: baixa latência, mas a notificação se perde se o relay estiver fora, então ainda precisaria do polling como rede de segurança. Fica como otimização possível sobre o polling.
- Relays particionados por `hash(aggregate_id) mod N`, cada um com sua trava e seu filtro: mantém a ordem por agregado e escala a vazão em N, mas exige configurar N, redistribuir ao mudar N e vigiar partições ociosas. Fica para quando a vazão de um relay não bastar.
- CDC com Debezium: ordem e vazão melhores e nenhuma carga de polling, mas adiciona Kafka Connect e configuração de replicação lógica para operar. Entrou como alternativa no PR 8.3 (seção abaixo) e foi comparado com números.

## Prova

`docs/benchmarks/outbox/atomicity-and-concurrency.md`: atomicidade, ordem por agregado, poison e dois relays concorrentes sobre 10 000 eventos (0 duplicados, ordem por agregado mantida, 13,6 s com 1 ms de ack por publicação).

`docs/benchmarks/outbox/broker-down.md`: broker Kafka pausado por 60 s com 100 eventos pendentes, 0 publicados durante a queda, 100 entregues depois, zero perda.

## Atualização (PR 8.3): CDC com Debezium como alternativa medida

`radar.outbox.mode` escolhe quem publica: `polling` (padrão, o relay acima) ou `cdc`. Nunca os dois: com `cdc` o bean `OutboxRelay` não existe e o app só grava; quem publica é o Debezium, no perfil `cdc` do compose (`PROFILE=cdc make up`).

**O polling não paga nada pelo CDC existir.** O Flyway não cria coluna, função, tabela nem publicação do CDC. Tudo isso vem de `infra/cdc/enable-cdc.sql` (idempotente, numa transação, com `disable-cdc.sql` como par), aplicado só por quem liga o CDC: o runbook, o serviço `cdc-setup` do perfil `cdc` e o teste de integração. A tabela do polling fica exatamente como antes.

**Como funciona.**

- Kafka Connect em modo standalone (imagem `quay.io/debezium/connect:3.6.3.Final` fixada por digest) com o conector Postgres, `pgoutput` e o Outbox Event Router. Um processo, sem tópicos internos nem chamada REST de cadastro; a senha vem do arquivo de segredo por `DirectoryConfigProvider`, nunca das propriedades (`infra/cdc/`).
- `enable-cdc.sql` cria a coluna gerada `envelope` (o envelope que o relay monta a partir das outras colunas, calculado pelo banco com a função `outbox.build_envelope`, marcada `IMMUTABLE` porque uma coluna gerada exige e porque vale para os tipos de argumento usados: `jsonb_build_object` é só `STABLE` por aceitar qualquer tipo), a publicação `radar_outbox` (só `INSERT`, só as colunas que o roteador lê) e a tabela `cdc_heartbeat`. O valor do registro é a coluna `envelope`, sem expansão de JSON; chave `aggregate_id`; tópico por `aggregate_type` (`RegexRouter`).
- Exige PostgreSQL 18: coluna gerada em lista de colunas de publicação só existe a partir da 18. No 17 o `CREATE PUBLICATION` falha (`cannot use generated column "envelope" in publication column list`, verificado na 17.11); o script confere a versão antes de mexer em qualquer coisa e não deixa a coluna pela metade. Só quem liga o CDC depende disso; o polling roda em qualquer versão que o projeto suporte. Também exige `wal_level=logical` no servidor (reinício).
- A coluna guarda uma segunda cópia do payload por linha, só enquanto o CDC estiver ligado (7 dias de retenção).
- A limpeza apaga por `created_at` no modo `cdc` (ninguém grava `published_at`) e continua só nas publicadas no modo `polling`. Isso não perde evento no CDC: o slot segura o WAL do `INSERT` até o conector confirmar. O risco real é o slot ser invalidado por `max_slot_wal_keep_size` (1 GB no compose), e a partir daí o conector não retoma (precisa voltar ao polling e apagar os offsets).
- No modo `cdc` os medidores `outbox_pending`, `outbox_oldest_age_seconds` e `outbox_poison_pending` ficam em zero e o atraso é `outbox_replication_slot_lag_bytes`; `outbox_replication_slot_active` diz se alguém lê o slot. Alertas: `OutboxReplicationSlotLagHigh` (mais de 256 MiB ou slot ausente) e `OutboxReplicationSlotInactive` (sem leitor por 5 minutos).
- Batimento: com o outbox parado e outras tabelas recebendo escrita, o servidor não envia nada ao conector e o slot não avança, então o WAL das outras tabelas fica retido. Medido: só com `heartbeat.interval.ms` o lag ficou em 64 MB por mais de 30 s. O conector escreve a cada 10 s em `outbox.cdc_heartbeat` (publicada, com a limpeza no mesmo comando) e o lag caiu de 61 MB para 112 bytes em cerca de 16 s.

**Escolhas e o que foi descartado.**

- Debezium Server (mais leve no papel) foi descartado: o formato de cabeçalho `json` do Server grava as strings entre aspas (`"uuid"`), e os consumidores leem os cabeçalhos como texto puro. O Kafka Connect com `StringConverter` grava igual ao relay.
- A expansão de JSON do roteador (`table.expand.json.payload`) foi descartada: ela infere o esquema de cada mensagem, descarta `null` e `[]` e, com um array de números mistos (`[1, 2.5]`), devolve o payload como texto escapado, só com um aviso no log. O envelope com `traceparent` nulo também saía com o texto `traceparent` no lugar do `null` (o conector emite o nome da coluna para uma coluna gerada nula). Por isso o valor é o `jsonb` pronto e o `traceparent` não vai como cabeçalho (os consumidores usam o corpo quando o cabeçalho falta).
- O papel de replicação separado ficou de fora: o conector usa o usuário `radar` e o mesmo segredo `db_password`. Um papel `REPLICATION` com senha própria é o endurecimento seguinte.
- Um `aggregate_type` sem regra no `RegexRouter` vai para um tópico com o nome do tipo, em vez de falhar como o relay faz; um tipo novo exige a regra em `infra/cdc/connector.properties` junto de `radar.outbox.topics.*`.

**Dados** (10 000 eventos, mesma máquina, duas execuções, `docs/benchmarks/cdc/polling-vs-debezium.md`; a tabela é a segunda, com o polling sem a coluna):

| Cenário | polling p50 / p95 | cdc p50 / p95 |
|---|---|---|
| 100 eventos/s | 747 / 1 048 ms | 501 / 848 ms |
| 250 eventos/s | 10 000 / 18 419 ms | 487 / 846 ms |
| rajada de 10 000 | 28 209 / 55 228 ms | 558 / 1 003 ms |

O relay sustenta cerca de 170 eventos/s (publicação síncrona por mensagem); acima disso forma fila. O CDC gera de 40% a 60% do WAL do polling (12 a 15 MB contra 25 MB, com e sem a coluna no polling), tem CPU de Postgres da mesma ordem e custa um componente a mais (348 a 464 MiB, imagem de 2,4 GB), 7 passos de runbook para ligar (4 para voltar) e o risco de slot parado reter WAL.

**Decisão.** O polling continua o padrão. A coleta do PNCP é limitada a 0,2 requisição por segundo no cliente (ADR-0017), e a taxa de eventos fica abaixo de 100 por segundo. Nessa carga o CDC ganha cerca de 250 ms no p50 (as duas execuções deram 709 e 747 ms contra 492 e 501 ms), mas ambos ficam abaixo de 1,1 s no p95; o ganho é estável e pequeno, e o preço é um componente a mais e um slot para vigiar. O CDC fica pronto e testado (`CdcEquivalenceIT`, que passa os dois modos pelas mesmas asserções, fora do verify padrão: `make cdc-test`) e documentado.

**Quando trocar para `cdc`.**

- A taxa sustentada de eventos se aproxima de 100 por segundo (o relay satura perto de 170) e envio em lote no relay não bastar.
- A latência de saída precisa ficar abaixo do que o polling de 500 ms entrega e a fila do relay seria o gargalo.
- O custo do `UPDATE` por linha e da consulta de polling no Postgres passar a pesar (o CDC gera de 40% a 60% do WAL).

Para trocar e voltar, siga o runbook ([Trocar o modo do outbox](../runbook.md#trocar-o-modo-do-outbox)); para vigiar o slot, [Slot do CDC atrasado](../runbook.md#slot-do-cdc-atrasado). Em produção prefira Kafka Connect distribuído (alta disponibilidade e offsets no Kafka) ao standalone usado aqui.
