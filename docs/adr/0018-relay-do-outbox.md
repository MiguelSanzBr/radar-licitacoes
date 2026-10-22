# ADR-0018: relay do outbox por polling, serializado por advisory lock

- Status: aceita (será atualizada com dados medidos no M8)
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
- CDC com Debezium: ordem e vazão melhores e nenhuma carga de polling, mas adiciona Kafka Connect e configuração de replicação lógica para operar. Entra como alternativa no PR 8.3 e será comparado com números do M8.

## Prova

`docs/benchmarks/outbox/atomicity-and-concurrency.md`: atomicidade, ordem por agregado, poison e dois relays concorrentes sobre 10 000 eventos (0 duplicados, ordem por agregado mantida, 13,6 s com 1 ms de ack por publicação).

`docs/benchmarks/outbox/broker-down.md`: broker Kafka pausado por 60 s com 100 eventos pendentes, 0 publicados durante a queda, 100 entregues depois, zero perda.
