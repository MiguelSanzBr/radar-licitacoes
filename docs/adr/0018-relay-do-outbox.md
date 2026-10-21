# ADR-0018: relay do outbox por polling com SKIP LOCKED

- Status: aceita (será atualizada com dados medidos no M8)
- Data: 2026-10-21

## Contexto

O contexto `ingest` grava o agregado e o evento na mesma transação (tabela `outbox.outbox_event`). Alguém precisa levar as linhas até o Kafka sem perder evento e sem travar a coleta. As opções são ler a tabela por polling, acordar o relay com `LISTEN/NOTIFY` ou ler o WAL com CDC (Debezium).

## Decisão

Polling com `SELECT ... WHERE published_at IS NULL AND attempts < 10 ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`, em uma transação por lote, a cada 500 ms (e em laço enquanto os lotes vierem cheios).

- Publicação síncrona, em ordem de `id`, e só depois `published_at = now()` no mesmo commit que libera as travas.
- Falha em uma mensagem: `attempts + 1` e `last_error` (até 2000 caracteres); as mensagens seguintes do mesmo `aggregate_id` no lote são puladas, os outros agregados continuam.
- Com 10 tentativas a linha sai da consulta, a métrica `outbox_poison_total` sobe e o resto da fila não é bloqueado. A linha fica na tabela para investigação.
- Limpeza diária de publicados com mais de 7 dias, em lotes de 10 000, sempre com `published_at IS NOT NULL`.
- O módulo `outbox` é genérico (não importa nada de `ingest`) para o contexto `alerts` reutilizar.

## Garantias e limites

- Entrega ao menos uma vez. Se o processo cair entre o ack do broker e o commit, o lote é publicado de novo. Os consumidores são idempotentes pelo Inbox (`processed_event`).
- Com um relay ativo, a ordem por agregado é mantida. Com dois ou mais relays concorrentes, a ordem por agregado entre lotes não é garantida: o relay A pode estar segurando o evento 50 enquanto o relay B publica o evento 150 do mesmo agregado. Os consumidores toleram isso pela guarda de `source_version` (PR 1.9), que descarta versão mais antiga que a já aplicada.
- A implantação recomendada é um relay ativo. Mais de um serve para continuidade (o segundo assume se o primeiro cair) e para drenar backlog, não para ganhar ordem.
- Depois do poison, um evento posterior do mesmo agregado deixa de esperar pelo evento envenenado e pode ser publicado antes dele. É a consequência de não bloquear a fila; a guarda de versão do consumidor cobre.
- Falha de uma mensagem é `PublishException` ou qualquer `RuntimeException` do publicador: as duas contam tentativa, gravam `last_error` (`e.toString()`, até 2000 caracteres) e isolam o agregado no lote. Só um `Error` aborta o lote, e a transação desfaz tudo. `outbox_poison_total` é incrementado depois do commit, então um rollback e nova tentativa não contam duas vezes.
- A transação do relay fica aberta (com as travas das linhas) enquanto o publicador espera o broker, então o tempo do lote é limitado pelo timeout do publicador: no PR 1.7 o produtor Kafka define `delivery.timeout.ms` e `max.block.ms` curtos. Numa queda geral do broker o lote deve abortar cedo, em vez de esgotar o timeout mensagem a mensagem e contar tentativa de todas; o PR 1.7 implementa isso.
- Métricas: `outbox_pending` (não publicados), `outbox_oldest_age_seconds` (a mais antiga ainda tentável, sem as linhas no limite de tentativas) e `outbox_poison_pending` (não publicados no limite de tentativas, a vigiar por alerta).

## Alternativas

- `LISTEN/NOTIFY`: baixa latência, mas a notificação se perde se o relay estiver fora, então ainda precisaria do polling como rede de segurança. Fica como otimização possível sobre o polling.
- CDC com Debezium: ordem e vazão melhores e nenhuma carga de polling, mas adiciona Kafka Connect e configuração de replicação lógica para operar. Entra como alternativa no PR 8.3 e será comparado com números do M8.

## Prova

`docs/benchmarks/outbox/atomicity-and-concurrency.md`: atomicidade, ordem por agregado, poison e dois relays concorrentes sobre 10 000 eventos (0 duplicados em 6,0 s).
