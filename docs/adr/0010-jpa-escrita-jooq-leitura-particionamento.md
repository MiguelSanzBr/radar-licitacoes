# ADR-0010: JPA na escrita, jOOQ na leitura, tabela particionada por data de publicação

- Status: aceita
- Data: 2026-10-18

## Contexto

O contexto `ingest` guarda o agregado `Procurement` com histórico de situações, trava otimista e um JSON bruto do PNCP. A busca (`search`) lê projeções, nunca o agregado. A tabela `ingest.procurement` cresce sem parar (todas as licitações do PNCP), e o que se consulta e se arquiva por mês é a data de publicação.

## Decisão

- Lado de escrita em JPA (Hibernate): entidades só no adapter `ingest.adapter.out.jpa`, o domínio não conhece anotação. `@Version` (inteiro) dá a trava otimista, o `raw` é `jsonb` mapeado como `String`, e o histórico é uma coleção só de inserção.
- Lado de leitura em jOOQ, a partir do schema do Flyway, sobre as projeções do contexto `search`. Escrita e leitura não compartilham modelo (CQRS).
- `ingest.procurement` é particionada por `RANGE (published_at)`, uma partição por mês (limites em UTC), com chave primária composta `(numero_controle_pncp, published_at)`. A função `ingest.ensure_partition(month)` cria a partição do mês se faltar e é idempotente. A migration cria de 2024-01 a 2027-12.
- `published_at` é fixado na descoberta e nunca muda. O domínio identifica a licitação só por `numero_controle_pncp`, e o repositório monta a chave composta com o `published_at` do agregado.
- Não existe partição `DEFAULT`: inserir em mês sem partição falha alto (o job mensal da PR 4.5 depende disso para não esconder o esquecimento).
- Sem índice extra para `findById(ProcurementId)`, que não conhece o `published_at`: a chave primária `(numero_controle_pncp, published_at)` é um btree por partição e atende a igualdade pela coluna líder. Um teste faz `EXPLAIN` da consulta e confirma o uso do índice da chave primária.
- O histórico não é uma associação JPA: a tabela de histórico só tem `numero_controle_pncp` (sem a chave de partição) e o Hibernate não consegue juntar por parte de uma chave composta. O repositório carrega as licitações e todo o histórico em duas consultas (`where numero in (...) order by recorded_at, id`, em lotes de 1000 ids), agrupa no mapper e insere as linhas novas de histórico explicitamente.
- A atualização de `last_seen_at` (licitação vista de novo, sem mudança) não incrementa a versão de trava (`@OptimisticLock(excluded = true)`), para uma coleta repetida não invalidar quem está gravando mudança real. Descoberta concorrente da mesma licitação (violação da chave primária) vira `OptimisticLockingFailureException`, a mesma semântica de nova tentativa de uma gravação defasada.
- As colunas `dispute_mode_name`, `legal_basis_name` e `revision` estendem o §3.4 do plano: o agregado precisa restaurar o nome do modo de disputa, o nome do enquadramento legal e o contador de revisão (versão do envelope de evento), e as colunas originais só guardam os ids.

## Alternativas rejeitadas

- Tabela não particionada com índice BRIN em `published_at`: mais simples de operar e permite chave primária só em `numero_controle_pncp`, mas arquivar ou descartar um mês vira `DELETE` em massa com bloat e `VACUUM`, enquanto com partições vira `DETACH`/`DROP`. O BRIN também só ajuda quando a ordem física acompanha `published_at`, o que as atualizações em linha desfazem com o tempo.
- Partição `DEFAULT`: absorveria inserts sem partição, mas esconderia o esquecimento de criar o mês e engordaria uma partição sem limite.
- `pg_partman`: dependência de extensão a mais para um caso que uma função SQL de 10 linhas resolve.

## Consequências

- Não há unicidade global de `numero_controle_pncp` no banco: a chave composta só garante unicidade dentro da partição. A unicidade lógica depende de o `published_at` ser imutável e de o repositório sempre buscar por número antes de descobrir.
- `findById` não conhece o `published_at`, então consulta o índice da chave primária de todas as partições (custo proporcional ao número de meses, hoje 48).
- Esquecer de criar a partição do mês seguinte derruba a ingestão daquele mês; isso é intencional e é vigiado pelo job `ScheduledPartitionMaintenance` (diário, cria o mês atual e os próximos 3), pela métrica `ingest_partitions_ahead` e pelo alerta `ParticaoFutura`. O incidente simulado que motivou isso está em `docs/post-mortem/2026-10-22-particao-ausente.md`.
- O histórico se liga à licitação por número, sem chave estrangeira, e depende do repositório para manter a consistência.

## Prova

- `JpaProcurementRepositoryIT`, `ProcurementPartitionsIT`, `ProcurementNPlusOneIT` e `ProcurementEntityMapperTest` em `core/src/test/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/jpa/`.
- Contagem de statements antes e depois: `docs/benchmarks/jpa/n-plus-one.md`.

## Projeções e Inbox

O consumidor `search.adapter.in.kafka.ProcurementEventsListener` lê `radar.procurement.v1` no grupo `search-projector` (`radar.search.projector.group`), com `String` na entrada, confirmação manual e fábrica de containers própria do `search`. O JSON do envelope vira registros de `search.application` (o `search` não importa nada de `ingest` nem de `outbox`; um teste do Modulith prende isso). `eventType` desconhecido é registrado e confirmado, para o produtor poder evoluir. Mensagem venenosa (JSON malformado, campo obrigatório ausente, `aggregateVersion` ausente em vez de virar versão 0) não é repetida: o tratador de erros a envia na hora para `radar.procurement.v1.dlq` (ADR-0026). Queda do banco (conexão, tempo esgotado, trava ou transação, em qualquer ponto da cadeia de causas) é repetida sem limite a cada 5 segundos, para uma queda nunca mandar registro para a DLQ; qualquer outra falha, inclusive dado que o banco rejeita, tem 3 tentativas de 1 segundo e depois vai para a DLQ. O contexto de trace vem do cabeçalho Kafka `traceparent`, com o campo do envelope como reserva. `radar.search.projector.enabled=false` desliga o consumidor.

Inbox e projeção ficam na mesma transação do gerenciador do lado de leitura (`searchTransactionManager`): `INSERT INTO search.processed_event ... ON CONFLICT DO NOTHING`, e só se inseriu a linha o evento é aplicado. A confirmação do offset vem depois do commit, então uma queda no meio reentrega o evento e o inbox o descarta.

- `Discovered` e `Updated` fazem upsert em `procurement_search_view` e `procurement_detail_view` com `WHERE excluded.source_version > atual.source_version`: versão igual ou menor nunca sobrescreve (plano, §10 item 5). O `search_vector` é calculado no próprio upsert (A = objeto truncado em 100 000 caracteres, B = órgão e unidade, C = município e modalidade) porque `unaccent` não é `IMMUTABLE`. O `value_band` sai de `ProcurementView.valueBand()`.
- O detalhe guarda o snapshot recebido mais o `history` (lista de transições) e, depois do fechamento, `closedAt`. O `etag` é o SHA-256 do texto canônico do `jsonb` gravado, calculado no mesmo comando SQL.
- `SituationChanged` e `Closed` atualizam o que já existe: status e histórico, ou `closedAt`. O agregado emite `Updated` e `SituationChanged` com a mesma `aggregateVersion`, então esses dois eventos aplicam de versão igual para cima; um evento de versão menor que a projetada é descartado e conta como processado.
- Se a linha da licitação ainda não existe (evento de situação ou fechamento antes do `Discovered`), nada é gravado e a transação inteira desfaz, inclusive o `processed_event`; o consumidor confirma o offset, registra um aviso e incrementa `projection_deferred_total`. Isso não se recupera sozinho: a garantia vem da ordem do relay (outra tarefa serializa os relays com um advisory lock, e a ordem por agregado passa a valer), que torna o `DEFERRED` inalcançável na operação normal. O contador existe para provar que continua zero. O mesmo conserto fecha outro buraco: um `SituationChanged` que chegasse antes do `Updated` da mesma versão subiria o `source_version` e faria o `Updated` ser descartado pela guarda estrita.
- `projection_checkpoint` guarda o último evento e o offset aplicados; evento repetido não mexe nele.
- A métrica `projection_lag_seconds` (histograma) mede o tempo entre `occurredAt` do envelope e a projeção.

Alternativa rejeitada: `tsvector` como coluna gerada, por causa do `unaccent` (o wrapper imutável fica registrado no ADR-0011).

Prova: `ProjectionIdempotencyIT` (mesmo lote de 200 envelopes três vezes, esquema `search` byte a byte idêntico), `JooqProjectionStoreIT` (versão antiga depois da nova, situação antes do `Discovered`, vetor sem acento, objeto de 2 milhões de caracteres) e `docs/benchmarks/cqrs/idempotency.md`.

## Troca de coluna sem downtime: expand-contract (valor estimado em centavos)

Decisão da PR 2.9, acrescentada a esta ADR porque o que muda é o schema de `ingest.procurement` que ela define. `estimated_value numeric(18,2)` ganha a coluna irmã `estimated_value_cents bigint` e a leitura passa para ela sem parar a aplicação e sem uma migration que reescreva a tabela de uma vez. O motivo de escolher centavos inteiros é o `Money` do domínio: o valor circula em centavos (`Money.cents()`) e comparar inteiros dispensa a escala do `numeric`. O que importa aqui é o método, que serve para qualquer troca de coluna.

Só `ingest.procurement` entra. As cópias de leitura, `search.procurement_search_view` (V5) e a coluna de `alerts` (V11), mantêm o próprio `estimated_value`: são projeções reconstruídas dos eventos e não dependem da coluna da escrita.

### Sequência de releases

1. **Expand** (release atual, migration V14): `SET LOCAL lock_timeout = '5s'` (o `ALTER` pede trava exclusiva; atrás de uma transação longa ele falharia em 5 s em vez de enfileirar todas as consultas seguintes, e o deploy é repetido), `ADD COLUMN estimated_value_cents bigint` (só catálogo, propaga para as partições), o gatilho `sync_estimated_value`, `BEFORE INSERT OR UPDATE` no pai particionado (vale para todas as partições, as de hoje e as futuras; PG 13 ou mais) e o índice parcial `procurement_cents_pending_idx` das linhas ainda sem centavos. O gatilho faz a dupla escrita nos dois sentidos: só o valor mudou, calcula os centavos; só os centavos mudaram, calcula o valor; os dois mudaram e discordam, falha alto (`check_violation`). O código novo escreve só `estimated_value`; os centavos vêm do gatilho.
2. **Backfill**: o job `EstimatedValueCentsBackfill` (desligado por padrão, `RADAR_INGEST_VALUE_CENTS_BACKFILL_ENABLED=true`) preenche as linhas antigas em lotes de 5 000 por chave primária, uma transação curta por lote. A métrica `ingest_value_cents_pending_rows` mostra o que falta; ao chegar a 0 o passo terminou, e os ticks seguintes só fazem um `EXISTS` sobre o índice parcial, que está vazio. O job não tem trava entre instâncias: duas instâncias repetem trabalho sem dano, então ligue-o em uma só.
3. **Flag**: com o backfill concluído, o operador liga `READ_VALUE_CENTS` em `/ops/togglz` e o mapper passa a ler o `Money` dos centavos. Reverter é desligar a flag, sem deploy. A leitura cai para o `numeric` quando a linha ainda não tem centavos, então ligar a flag antes do fim do backfill é seguro (sem isso, o próximo `save` de uma linha sem centavos gravaria `NULL` e o gatilho apagaria os dois campos); a ordem 2 antes de 3 é uma recomendação.
4. **Contract** (release seguinte, depois de uma release inteira com a flag ligada): `core/src/main/resources/db/contract/V16__drop_estimated_value.sql.next` derruba o índice dos pendentes, o gatilho, a função e a coluna `estimated_value`. O mesmo PR remove o campo da entidade e do mapper e a flag. O arquivo existe desde já, com a extensão `.next` e fora de `db/migration` para o Flyway não o executar hoje; `ContractScriptIT` roda o script numa transação de teste para provar que ele é válido contra o schema expandido.

Rollback: até o passo 3 a release anterior continua funcionando contra o schema expandido (ela ignora a coluna e o gatilho a mantém); no passo 3, desligar a flag; só o passo 4 é sem volta, e por isso espera uma release inteira.

### Decisões de implementação

- O gatilho fica no pai particionado: o Postgres o clona em cada partição, inclusive nas criadas por `ingest.ensure_partition` depois da migration. `EstimatedValueTriggerIT` confirma com um `insert` direto numa partição existente e noutra criada depois.
- O backfill usa a chave primária completa `(numero_controle_pncp, published_at)` como cursor, não o `ctid`: o `ctid` se repete entre partições. O cursor anda para frente e a escolha do lote lê o índice parcial dos pendentes (`Merge Append` sobre `Index Only Scan` por partição, sem `Sort`: `JdbcEstimatedValueCentsStoreIT` confere o `EXPLAIN`), então nenhum lote relê o que os anteriores preencheram.
- A entidade JPA mapeia `estimated_value_cents` como somente leitura (`insertable = false, updatable = false`) e `@Generated` em `INSERT` e `UPDATE`: o Hibernate lê de volta o que o gatilho gravou, com `RETURNING estimated_value_cents` no mesmo statement (sem consulta extra), então a licitação recém-salva já traz os centavos mesmo com a flag ligada.
- A flag chega ao `ingest` pela porta de aplicação `FeatureFlags` (adaptador `OpsFeatureFlags` sobre `ops.Flags`), o mesmo padrão do `alerts`: o `ingest` não importa o Togglz, e uma regra do ArchUnit (`ingestDoesNotKnowTogglz`, com fixture) o garante.
- A imagem da release anterior (`radar-licitacoes:prev`, construída do commit anterior ao expand) sobe contra o schema V14: o Flyway aceita a migration aplicada que ela não conhece (`ignoreMigrationPatterns` padrão `*:future`), verificado no log pelo teste de compatibilidade. O plano cita a imagem no GHCR; sem repositório remoto, a tag local a substitui.

### Consequências

- Durante o expand, cada linha inserida custa a execução do gatilho (de 0% a 10% a mais na inserção de 100 000 linhas medida; o custo no `UPDATE` não ficou acima do ruído do host e não está medido com precisão) e o backfill dobra o arquivo das partições com linhas antigas até um `VACUUM FULL`; o índice parcial cresce com as linhas pendentes e sua construção na V14 segura as escritas da tabela: números em `docs/benchmarks/db/vacuum-bloat.md` e `expand-contract.md`.
- Dois campos com o mesmo dado convivem por uma release: uma escrita que tente fixar os dois com valores diferentes falha em vez de gravar o desvio.

### Prova

`EstimatedValueTriggerIT`, `JdbcEstimatedValueCentsStoreIT`, `EstimatedValueCentsBackfillTest`, `ReadValueCentsFlagIT`, `ProcurementEntityMapperTest`, `JpaProcurementRepositoryIT`, `ContractScriptIT`, `ExpandMigrationLockIT` e `PreviousReleaseCompatIT` (`make compat`). Medições e resultado do teste de compatibilidade: `docs/benchmarks/db/expand-contract.md` e `docs/benchmarks/db/vacuum-bloat.md`.

## Replay

O modelo de leitura se reconstrói do zero a partir do tópico `radar.procurement.v1`: `POST /ops/replay` (papel operator, `make replay`) responde 202 e roda em thread virtual; `GET /ops/replay/last` devolve o estado da última execução: `RUNNING`, `FAILED` (com `error`) ou `DONE` (com o `ReplayReport`: `events`, `duration`, `eventsPerSecond`, `startedAt`, `finishedAt`), e 404 se nenhuma rodou desde que a instância subiu. `make replay` espera até `DONE` (imprime o relatório) ou `FAILED` (sai com erro). O campo `projection` do `ReplayRequest` do contrato é aceito e ignorado: o replay sempre reconstrói todas as projeções (limite conhecido). Um replay por vez por instância: a trava é em memória, então 409 só vale dentro da instância que recebeu o pedido; com mais de uma instância o operador deve chamar uma só. O caso de uso é `search.application.ReplayUseCase`.

Procedimento, nesta ordem:

1. Para o container do `ProcurementEventsListener` (id `search-projector-listener`), para ele não disputar as tabelas. O registro em curso termina antes.
2. Abre um `KafkaConsumer` do `ConsumerFactory` do Boot, em grupo novo `search-replay-<epoch ms>`, atribuído (sem rebalance) às 12 partições e posicionado no início, e fixa os `endOffsets` daquele instante. Isso vem antes de apagar qualquer coisa: se o broker não responde, as projeções ficam intactas.
3. Numa transação: `TRUNCATE` de `procurement_search_view`, `procurement_detail_view`, `procurement_stats_view` e `projection_checkpoint`, e `DELETE` das linhas de `processed_event` do `search-projector` (outros consumidores do schema mantêm as suas).
4. Lê até cada partição alcançar o offset final fixado, projetando em lotes de 500 eventos por transação com a mesma lógica do projetor (`ProcurementProjector.projectAll`, sem SQL duplicado). Mensagem venenosa é registrada e pulada, como no consumidor vivo. O lag não é registrado (eventos antigos não são atraso); `projection_deferred_total` continua contando.
5. Religa o container vivo (se estava rodando antes do replay; um container que o operador tinha parado continua parado), que segue dos offsets que o grupo `search-projector` já tinha confirmados. O replay nunca confirma offset no grupo vivo. O que o vivo relê do que o replay já projetou é descartado pelo inbox (`processed_event` foi reconstruído), e o que chegou depois dos offsets fixados só o vivo processa. Se o replay falha, o vivo é religado do mesmo modo e as projeções ficam como estavam no ponto da falha: repita o replay.

Durante o replay as consultas veem as tabelas vazias e depois parciais: é uma operação de manutenção, não de rotina.

Por que o tópico e não o outbox: as linhas publicadas do outbox são apagadas depois de 7 dias (`OutboxCleanup`), então só o tópico guarda a história inteira. Exigência derivada: o tópico `radar.procurement.v1` tem `retention.ms=-1` (retenção infinita), configurado no `NewTopic` de `KafkaOutboxConfiguration`. Um tópico com retenção finita tornaria o replay uma reconstrução parcial sem aviso. A DLQ continua com a retenção padrão.

Prova: `ProjectionReplayIT` (50 mil eventos sintéticos: projeta ao vivo, despeja as visões, roda o replay e despeja de novo; iguais, linha a linha por SHA-256, ignorando só as colunas de horário), `JooqProjectionReplayIT` (lote com evento adiado no meio, `reset` preserva outros consumidores) e `docs/benchmarks/replay/`.

## Níveis de isolamento por caminho de escrita

Decisão da PR 2.8, acrescentada a esta ADR porque o que se escolhe é o nível de cada transação que grava no schema que ela define. O padrão da aplicação é **READ COMMITTED**, o do Postgres: nenhuma conexão do Hikari e nenhuma transação sobe o nível por conta própria. Cada caminho de escrita ganha a garantia de outra forma, e três testes com duas transações sincronizadas mostram o que acontece sem ela. Números e montagem: `docs/benchmarks/db/isolation.md`.

| Caminho | Nível | Como a concorrência é resolvida | Por quê |
|---|---|---|---|
| `ingest.procurement` (JPA) | READ COMMITTED | trava otimista com `@Version`; conflito vira `OptimisticLockingFailureException` e o chamador relê e reaplica; descoberta concorrente da mesma chave perde na chave primária e vira a mesma exceção | sem a versão, a segunda gravação apaga a primeira (200 de 200 corridas no teste); o conflito só ocorre quando dois fluxos tocam a mesma licitação ao mesmo tempo e relê-lo é barato, então travar a linha com `SELECT ... FOR UPDATE` custaria espera sem ganho |
| `alerts.alert_delivery` | READ COMMITTED | chave primária `(saved_search_id, procurement_id)` e `INSERT ... ON CONFLICT DO NOTHING` | uma tentativa por escritor, sem falha e sem laço de retry; a alternativa em SERIALIZABLE precisou de 1,6 vezes mais tentativas e de uma pausa aleatória no retry para não esgotá-las, com o mesmo resultado |
| projeções de `search` (inbox e upsert) | READ COMMITTED | `processed_event ... ON CONFLICT DO NOTHING` e `WHERE source_version <` na mesma transação | a unicidade e a ordem já estão em restrição e em guarda de versão, nada pede nível maior |
| leitura que combina mais de uma consulta sobre o mesmo agregado (estatísticas) | REPEATABLE READ, só na transação que lê | instantâneo do primeiro comando; não trava quem escreve | sob READ COMMITTED duas leituras do mesmo conjunto deram respostas diferentes (10 linhas e 1000,00, depois 11 e 1150,00); a escolha do projetor de estatísticas fica registrada na PR 2.3 |

Alternativa rejeitada: SERIALIZABLE como nível padrão ou na entrega de alertas. Ele dá a garantia sem pedir restrição, mas cobra com falhas `40001` certas sob sobreposição (um escritor recusado em todas as 200 corridas) e exige retry com pausa; onde a unicidade cabe em uma restrição, a restrição é mais barata e mais simples. O nível fica reservado para regras que uma restrição não expressa.

Prova: `LostUpdateIT`, `NonRepeatableReadIT` e `SerializableDeliveryIT`, e `docs/benchmarks/db/isolation.md`.
