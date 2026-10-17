# ADR-0007: eventos em JSON com JSON Schema primeiro, Avro depois

- Status: aceita
- Data: 2026-10-17

## Contexto

Os contextos conversam por eventos (`ProcurementDiscovered`, `ProcurementUpdated`, `ProcurementSituationChanged`, `ProcurementClosed`, `AlertMatched`) publicados no Kafka via outbox. O evento carrega o snapshot completo do agregado, para que o replay do read side seja autossuficiente. É preciso escolher a serialização e como o contrato evolui sem quebrar consumidores. Avro com registry dá evolução verificada por máquina, mas exige o registry no caminho crítico antes de existir um segundo consumidor real.

## Decisão

Na camada 1, os eventos são JSON com um envelope comum (`eventId`, `eventType`, `schemaVersion`, `aggregateType`, `aggregateId`, `aggregateVersion`, `occurredAt`, `traceparent`, `payload`) e JSON Schema versionado em `contracts/events/*.schema.json`. O CI valida os schemas e os exemplos.

Na camada 3 (M8), entra Avro com Apicurio Registry e regra de compatibilidade `BACKWARD` por tópico. A migração é dual-format: o produtor passa a escrever Avro e o consumidor aceita JSON e Avro durante a transição. A escolha do registry e do formato binário fica na ADR-0024.

Regras de evolução já na camada 1: campo novo é opcional; remoção ou mudança de tipo exige `schemaVersion` novo; o consumidor ignora campos desconhecidos.

## Alternativas rejeitadas (e por quê)

- Avro desde o início: acopla o caminho crítico a um registry, aumenta o compose e o tempo de teste antes de haver evolução real para proteger. O ganho (compatibilidade verificada) só aparece quando o contrato muda.
- Protobuf: custo parecido com Avro e, para este caso, sem vantagem de evolução que justifique a troca. Comparação formal na ADR-0024.
- JSON sem schema: contrato implícito, quebra silenciosa entre produtor e consumidor.

## Consequências

- Mensagens JSON são maiores que Avro. Aceito: o volume do projeto é pequeno e o ganho de depuração (ler a mensagem em texto) é real.
- A compatibilidade é garantida por testes e pelo lint dos schemas, não por um registry, até M8.
- A fase dual-format exige que o consumidor leia dois formatos por um período, o que a M8 testa explicitamente.

## Prova

- PR 1.1 `feat(shared): value objects and envelope`: envelope e `DomainEvent` com testes de unidade e de propriedade.
- Job `contracts` do CI: validação dos `.schema.json` contra exemplos.
- PR 8.1 `feat(contracts): avro schemas and apicurio`: teste de evolução (campo opcional registra; remoção de campo obrigatório é rejeitada) e relatório em `docs/benchmarks/schema/evolution.md`.
- Compatibilidade do cliente do registry com Kafka 4: pesquisa em `docs/compat-matrix.md`, a verificar na M8.
