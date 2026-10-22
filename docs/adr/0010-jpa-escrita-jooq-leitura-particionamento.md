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
- Esquecer de criar a partição do mês seguinte derruba a ingestão daquele mês; isso é intencional e será vigiado pelo job mensal.
- O histórico se liga à licitação por número, sem chave estrangeira, e depende do repositório para manter a consistência.

## Prova

- `JpaProcurementRepositoryIT`, `ProcurementPartitionsIT`, `ProcurementNPlusOneIT` e `ProcurementEntityMapperTest` em `core/src/test/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/jpa/`.
- Contagem de statements antes e depois: `docs/benchmarks/jpa/n-plus-one.md`.

## Projeções e Inbox

O consumidor `search.adapter.in.kafka.ProcurementEventsListener` lê `radar.procurement.v1` no grupo `search-projector` (`radar.search.projector.group`), com `String` na entrada, confirmação manual e fábrica de containers própria do `search`. O JSON do envelope vira registros de `search.application` (o `search` não importa nada de `ingest` nem de `outbox`; um teste do Modulith prende isso). `eventType` desconhecido é registrado e confirmado, para o produtor poder evoluir. Mensagem venenosa (JSON malformado, campo obrigatório ausente, `aggregateVersion` ausente em vez de virar versão 0) não é repetida: o tratador de erros a registra e segue (a DLQ chega na PR 4.3). Falha de banco ou de transação (`DataAccessException` ou `TransactionException` em qualquer ponto da cadeia de causas) é repetida sem limite a cada 5 segundos, para uma queda do banco nunca descartar registro; qualquer outra falha tem 3 tentativas de 1 segundo. O contexto de trace vem do cabeçalho Kafka `traceparent`, com o campo do envelope como reserva. `radar.search.projector.enabled=false` desliga o consumidor.

Inbox e projeção ficam na mesma transação do gerenciador do lado de leitura (`searchTransactionManager`): `INSERT INTO search.processed_event ... ON CONFLICT DO NOTHING`, e só se inseriu a linha o evento é aplicado. A confirmação do offset vem depois do commit, então uma queda no meio reentrega o evento e o inbox o descarta.

- `Discovered` e `Updated` fazem upsert em `procurement_search_view` e `procurement_detail_view` com `WHERE excluded.source_version > atual.source_version`: versão igual ou menor nunca sobrescreve (plano, §10 item 5). O `search_vector` é calculado no próprio upsert (A = objeto truncado em 100 000 caracteres, B = órgão e unidade, C = município e modalidade) porque `unaccent` não é `IMMUTABLE`. O `value_band` sai de `ProcurementView.valueBand()`.
- O detalhe guarda o snapshot recebido mais o `history` (lista de transições) e, depois do fechamento, `closedAt`. O `etag` é o SHA-256 do texto canônico do `jsonb` gravado, calculado no mesmo comando SQL.
- `SituationChanged` e `Closed` atualizam o que já existe: status e histórico, ou `closedAt`. O agregado emite `Updated` e `SituationChanged` com a mesma `aggregateVersion`, então esses dois eventos aplicam de versão igual para cima; um evento de versão menor que a projetada é descartado e conta como processado.
- Se a linha da licitação ainda não existe (evento de situação ou fechamento antes do `Discovered`), nada é gravado e a transação inteira desfaz, inclusive o `processed_event`; o consumidor confirma o offset, registra um aviso e incrementa `projection_deferred_total`. Isso não se recupera sozinho: a garantia vem da ordem do relay (outra tarefa serializa os relays com um advisory lock, e a ordem por agregado passa a valer), que torna o `DEFERRED` inalcançável na operação normal. O contador existe para provar que continua zero. O mesmo conserto fecha outro buraco: um `SituationChanged` que chegasse antes do `Updated` da mesma versão subiria o `source_version` e faria o `Updated` ser descartado pela guarda estrita.
- `projection_checkpoint` guarda o último evento e o offset aplicados; evento repetido não mexe nele.
- A métrica `projection_lag_seconds` (histograma) mede o tempo entre `occurredAt` do envelope e a projeção.

Alternativa rejeitada: `tsvector` como coluna gerada, por causa do `unaccent` (o wrapper imutável fica registrado no ADR-0011).

Prova: `ProjectionIdempotencyIT` (mesmo lote de 200 envelopes três vezes, esquema `search` byte a byte idêntico), `JooqProjectionStoreIT` (versão antiga depois da nova, situação antes do `Discovered`, vetor sem acento, objeto de 2 milhões de caracteres) e `docs/benchmarks/cqrs/idempotency.md`.
