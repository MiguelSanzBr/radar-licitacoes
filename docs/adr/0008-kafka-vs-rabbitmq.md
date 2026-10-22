# ADR-0008: Kafka ou RabbitMQ como broker

- Status: aceita
- Data: 2026-10-17

## Contexto

O broker transporta os eventos de domínio do outbox até o read side (`search`) e até os alertas. Três requisitos pesam: (1) ordem por licitação (chave `numeroControlePNCP`); (2) reconstrução do read side relendo o histórico desde o offset 0 (replay), sem depender do write side; (3) mais de um consumidor independente do mesmo evento, cada um no seu ritmo. O projeto também precisa demonstrar semântica de mensageria que RabbitMQ esconde ou resolve de outro jeito (rebalance, offsets, retenção).

## Decisão

Apache Kafka 4.x em modo KRaft (sem ZooKeeper), com os tópicos `radar.procurement.v1` (12 partições, chave `numeroControlePNCP`), `radar.alert.v1` (6 partições, chave `subscriberId`) e as respectivas DLQs (`.dlq`). Produtor com `acks=all` e idempotência; consumidores com Inbox (`processed_event`) para absorver a entrega at-least-once.

## Alternativas rejeitadas (e por quê)

- RabbitMQ: o modelo de fila remove a mensagem após o consumo, então o replay do read side exigiria reler o outbox no Postgres ou republicar. Ordem estrita por chave com vários consumidores exige filas por chave ou o plugin de hash consistente. Streams do RabbitMQ cobririam o replay, mas trocariam um modelo maduro de log por outro menos usado, sem ganho aqui.
- Somente o outbox no Postgres, sem broker: serve para um consumidor, mas perde a independência entre consumidores e o desacoplamento de ritmo, e não exercita a camada de mensageria.
- Redis Streams: sem o ecossistema de grupos, retenção e DLQ que o projeto quer demonstrar, e Redis já tem outro papel (limite de taxa e cache).

## Consequências

- Custo operacional maior que o de uma fila simples: um broker KRaft no compose (`apache/kafka`, imagem única) e atenção a partições, rebalance e retenção.
- A ordem só vale dentro da partição; a chave é parte do contrato dos eventos (ADR-0007).
- Entrega at-least-once é a regra; idempotência no consumidor é obrigatória (Inbox).
- Num ambiente de custo zero o broker roda em container, não em serviço gerenciado.
- O tópico `radar.procurement.v1` é criado com `retention.ms=-1` (sem expiração), porque o replay do PR 1.10 relê do offset 0 e o snapshot completo no evento torna isso possível. Os outros tópicos ficam com a retenção padrão do broker.

## Prova

- PR 1.7 `feat(outbox): kafka publisher`: publica e lê de volta; broker pausado por 60 s com 100 eventos pendentes, 0 publicados durante a queda e 100 entregues depois, zero perda (`docs/benchmarks/outbox/broker-down.md`).
- PR 1.10 `feat(search): replay`: reconstrução das projeções relendo o tópico do offset 0.
- PR 8.4 `feat(messaging): rebalance, backpressure, ordering, dlq reprocess`: rebalance sem perda, ordenação por chave e DLQ.
- Compatibilidade com Testcontainers 2.0.5 e a imagem `apache/kafka:4.1.0`: `docs/compat-matrix.md` (round-trip real executado em 2026-10-17).
