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
