# Prova: troca de coluna sem downtime (expand-contract)

Data: 2026-10-23. Decisão e sequência de releases: `docs/adr/0010-jpa-escrita-jooq-leitura-particionamento.md`, seção "Troca de coluna sem downtime".

## O que foi trocado

`ingest.procurement.estimated_value numeric(18,2)` passa a ter a coluna irmã `estimated_value_cents bigint`. Só a tabela de escrita (particionada por mês) entra nesta troca. As cópias de leitura, `search.procurement_search_view` (V5) e a coluna de `alerts` (V11), guardam o próprio `estimated_value` e não mudam: são projeções reconstruídas a partir dos eventos, não dependem da coluna da escrita.

## Passos e comandos

| Passo | O que acontece | Como se verifica |
|---|---|---|
| 1. Expand (V14) | `SET LOCAL lock_timeout = '5s'`, `ADD COLUMN` (só catálogo, propaga para as 48 partições), gatilho `BEFORE INSERT OR UPDATE` no pai particionado com dupla escrita nos dois sentidos e o índice parcial `procurement_cents_pending_idx` das linhas que o backfill ainda precisa preencher | `EstimatedValueTriggerIT` (20 casos, inclusive inserção direta numa partição, partição criada depois, negativos e estouro de `bigint` para `numeric(18,2)`) e `ExpandMigrationLockIT` (a V14 desiste em 5 s atrás de uma transação longa e não aplica nada) |
| 2. Backfill | job `EstimatedValueCentsBackfill`, desligado por padrão: `RADAR_INGEST_VALUE_CENTS_BACKFILL_ENABLED=true`, lotes de `radar.ingest.value-cents-backfill.batch-size` (5000), métrica `ingest_value_cents_pending_rows` | `JdbcEstimatedValueCentsStoreIT` (12 000 linhas em duas partições, todas conferidas, e o `EXPLAIN` da escolha do lote) |
| 3. Flag | `READ_VALUE_CENTS` (Togglz, `/ops/togglz`, nasce desligada): ligada, o mapper lê o `Money` dos centavos e, se a linha ainda não tem centavos, do `numeric`; desligada, sempre do `numeric`. As escritas não dependem dela | `ReadValueCentsFlagIT` (vira a flag em tempo de execução), `ProcurementEntityMapperTest` e `JpaProcurementRepositoryIT` (os dois modos, a volta ao `numeric` e o salvamento e o fechamento de linha sem centavos com a flag ligada) |
| 4. Contract (release seguinte) | `core/src/main/resources/db/contract/V17__drop_estimated_value.sql.next`: derruba o índice dos pendentes, o gatilho, a função e a coluna `estimated_value` | `ContractScriptIT` roda o script dentro de uma transação de teste (o DDL desfaz) e confere o resultado |

O arquivo de contract tem a extensão `.next` e mora fora de `db/migration`: o Flyway não o vê hoje. O PR da release seguinte o move para `db/migration` (V17 ou o próximo número livre), remove o campo da entidade e a flag.

Linha lida com a flag ligada e ainda sem centavos (backfill incompleto) volta com o valor do `numeric`, não sem valor: ler a ausência como "sem valor estimado" faria o próximo `save` (a coleta, o fechamento de propostas) gravar `estimated_value = NULL`, e o gatilho então apagaria os dois campos. Por isso a ordem "backfill concluído antes de ligar a flag" é uma recomendação (a leitura continua correta se ela for desrespeitada), não uma exigência. Dois testes de integração salvam e fecham uma linha sem centavos com a flag ligada e conferem que `estimated_value` continua intacto.

O `@Generated` da entidade faz o Hibernate ler de volta os centavos que o gatilho gravou, com `RETURNING estimated_value_cents` no próprio `INSERT` e `UPDATE` (conferido no SQL do Hibernate): não há statement extra, e a licitação recém-salva já traz os centavos.

## Gatilho em tabela particionada

O gatilho é criado no pai (`CREATE TRIGGER ... ON ingest.procurement`), o que vale a partir do PG 13: o Postgres o clona em cada partição existente e em cada uma criada depois. Os testes confirmam as duas pontas: `insert` direto em `ingest.procurement_y2026m10` (sem passar pelo pai) e em `ingest.procurement_y2032m03`, criada por `ingest.ensure_partition` depois da migration, preenchem os centavos.

Regras (função `ingest.sync_estimated_value`):

- só `estimated_value` informado ou alterado: centavos = `round(valor * 100)`;
- só os centavos informados ou alterados: `estimated_value` = centavos / 100.0;
- os dois alterados e discordando: `RAISE EXCEPTION` com `check_violation`;
- linha de antes do expand que uma escrita qualquer toca, com centavos nulos e valor preenchido: os centavos são preenchidos (cura).

Custo no caminho de escrita, 100 000 linhas numa partição (Testcontainers, ver `vacuum-bloat.md` para o ambiente), três execuções com o host disputado por outras compilações:

| Execução | `INSERT` com o gatilho | `INSERT` sem o gatilho (`session_replication_role = replica`) | `UPDATE` de `last_seen_at` com o gatilho | `UPDATE` sem o gatilho |
|---|---|---|---|---|
| 1 | 1 942 ms | 1 951 ms | 2 182 ms | 2 618 ms |
| 2 | 1 889 ms | 1 725 ms | 1 715 ms | 2 120 ms |
| 3 | 2 981 ms | 2 745 ms | 8 591 ms | 7 617 ms |

No `INSERT` o gatilho custou de 0% a 10% (0, 9,5 e 8,6). No `UPDATE` que não toca nenhuma coluna de valor a diferença (de menos 19% a mais 13%) fica abaixo do ruído do host: o gatilho só compara duas colunas por linha e a medição não o separa do ruído. O custo do `UPDATE` não está medido com precisão; a comparação dos dois lados só diz que ele não é grande. (Uma medição anterior, com a V14 sem o índice parcial, deu de 18% a 24% a mais no `INSERT`; o ruído do host explica a diferença, e os números acima são os da V14 entregue.)

## Backfill em lotes: tempos medidos

Lote por chave primária, uma instrução por lote (portanto uma transação curta por lote): `WITH batch AS (SELECT ... WHERE (numero, published_at) > cursor AND cents IS NULL AND value IS NOT NULL ORDER BY chave LIMIT n), filled AS (UPDATE ... FROM batch ... RETURNING ...)`. A chave é a primária inteira, não o `ctid`: o `ctid` se repete entre partições e apontaria linhas erradas. O cursor anda para frente, então nenhum lote relê as linhas que os anteriores já preencheram.

O índice parcial `procurement_cents_pending_idx (numero_controle_pncp, published_at) WHERE estimated_value_cents IS NULL AND estimated_value IS NOT NULL` (V14, no pai, propaga para as partições) serve duas coisas. A escolha do lote lê só ele, já na ordem da chave: `JdbcEstimatedValueCentsStoreIT` faz o `EXPLAIN` e exige `Merge Append` sobre `Index Only Scan` por partição, sem nó `Sort` e sem `Seq Scan`. E o "sobrou alguma?" do job vira um `EXISTS` sobre o índice: depois do backfill ele fica vazio (o gatilho já preenche as linhas novas), então um tick do job parado custa quase nada e só roda a contagem quando há o que fazer. A métrica `ingest_value_cents_pending_rows` usa a contagem sobre o mesmo índice, e vai a 0 sem contagem no tick ocioso.

Custo do índice: as linhas sem centavos entram nele (a carga de 100 000 linhas sem centavos aumentou os índices da partição de 5,1 para 9,0 MiB), e construí-lo na V14 numa tabela grande segura as escritas durante a varredura de cada partição (`CREATE INDEX CONCURRENTLY` não vale no pai particionado); numa tabela com milhões de linhas pendentes, vale criá-lo por partição, fora da migration, antes do deploy.

O job não tem trava entre instâncias: duas instâncias com o job ligado fazem trabalho repetido, sem dano (cada `UPDATE` grava o mesmo valor e o gatilho é idempotente), mas disputam as mesmas linhas. Ligue o job em uma instância só.

Comando e ambiente: `./mvnw -Pbench test -pl core -am -Dtest=DbBloatBenchTest -Dsurefire.failIfNoSpecifiedTests=false` (o relatório sai em `core/target/db-bloat-bench.md`). 100 000 linhas sem centavos numa partição, Postgres 18.6 em contêiner próprio, `shared_buffers` 128 MB. Três execuções completas da V14 entregue, no mesmo host, com outras compilações rodando em paralelo (load average de 3 a 6).

Tempo total do backfill, em ms (e linhas por segundo):

| Lote | Execução 1 | Execução 2 | Execução 3 |
|---|---|---|---|
| 1 000 (101 lotes) | 2 565 (38 986/s) | 18 214 (5 490/s) | 2 670 (37 453/s) |
| 5 000 (21 lotes) | 2 805 (35 651/s) | 3 555 (28 129/s) | 3 965 (25 221/s) |
| 20 000 (6 lotes) | 2 608 (38 344/s) | 3 747 (26 688/s) | 3 493 (28 629/s) |
| 100 000 (2 lotes) | 6 538 (15 295/s) | 2 818 (35 486/s) | 3 888 (25 720/s) |

Duração de cada lote (mediana, p95 e máximo, em ms), mesmas execuções:

| Lote | Exec. 1 | Exec. 2 | Exec. 3 |
|---|---|---|---|
| 1 000 | 24,3 / 26,5 / 30,2 | 126,2 / 484,1 / 1 729,9 | 25,0 / 28,8 / 32,0 |
| 5 000 | 121,8 / 176,7 / 179,3 | 155,9 / 199,0 / 304,6 | 159,9 / 294,9 / 393,3 |
| 20 000 | 436,1 / 597,3 / 597,3 | 692,8 / 889,6 / 889,6 | 604,5 / 769,0 / 769,0 |
| 100 000 | 6 270 (mediana de 2) | 2 672 (mediana de 2) | 3 712 (mediana de 2) |

Leitura:

- Fora das execuções disputadas, o ritmo é de 25 mil a 39 mil linhas por segundo, quase independente do tamanho do lote. O tamanho do lote muda a duração de cada transação, não a vazão: 1 000 linhas seguram a transação por uns 25 ms, 5 000 por 120 a 160 ms, 20 000 por 440 a 700 ms.
- Os valores muito acima da mediana (a execução 2 de 1 000 linhas, com 18 s no total e um lote de 1,7 s; o lote único de 100 000 linhas da execução 1, 6,5 s) são disputa de disco do host, em execuções e tamanhos diferentes, e não propriedade do lote. Por isso o padrão é 5 000: transação de uns 150 ms, e um engasgo isolado atrasa só um lote. Um lote só de 100 000 linhas segura uma transação de vários segundos e não se recomenda.
- O backfill de 100 000 linhas da execução da tabela de bloat (lotes de 5 000) levou 3 365 ms, 3 007 ms e 4 474 ms.
- Em produção o tempo do backfill é o número de linhas dividido por esse ritmo, dividido de novo pelo que o job disputa com a coleta. Medimos uma partição de 100 000 linhas; a tabela inteira não foi medida.

Efeito colateral medido: cada linha preenchida é um `UPDATE` que cria nova versão, e nenhum foi HOT (0 de 100 000): as páginas estão cheias (fillfactor 100) e a coluna atualizada entra no predicado do índice dos pendentes. O backfill dobra o arquivo da partição até um `VACUUM FULL`. Os números estão em `vacuum-bloat.md`.

## Release anterior contra o schema expandido (`make compat`)

O plano pede a imagem da release anterior no GHCR. Não há repositório remoto, então a tag local `radar-licitacoes:prev` faz o papel dela.

Como a imagem foi construída:

```
git worktree add --detach ../radar-prev 4cf27a9
cd ../radar-prev && make image RADAR_TAG=prev
git worktree remove --force ../radar-prev
```

`4cf27a9` é o commit imediatamente anterior a este PR. O worktree temporário foi removido; a imagem ficou (586 MB).

`make compat` verifica que a imagem existe e roda `./mvnw -Pcompat verify -pl core -am -Dit.test=PreviousReleaseCompatIT` (`core/src/test/java/br/dev/miguelsanzbr/radar/compat/`, `@Tag("compat")`, fora do `verify` padrão e do gate de cobertura). O teste:

1. sobe um Postgres 18 novo e aplica pelo Flyway todas as migrations desta release (V1 a V14);
2. sobe o WireMock da fixture do PNCP (a mesma imagem fixada do compose, com 3 licitações reais de 2026-10-21 e uma segunda leitura que revisa o valor da primeira);
3. sobe `radar-licitacoes:prev` apontada para os dois, com a coleta incremental ligada a cada 3 s, sem broker (relay e ouvintes Kafka desligados);
4. confere que a aplicação anterior sobe, que o Flyway não gravou nada (`success = false` zero) e que o log traz "that is newer than the latest available migration" e "No migration necessary";
5. espera as 3 licitações que a release anterior gravou e confere que todas têm `estimated_value_cents` igual a `round(estimated_value * 100)`;
6. espera a segunda leitura, em que a release anterior faz `UPDATE` do valor da primeira licitação para 250 000,55, e confere `estimated_value_cents = 25000055`.

Resultado da execução real (2026-10-23), repetida depois do índice parcial e do `lock_timeout` na V14: 4 testes, 0 falhas, `BUILD SUCCESS`, cerca de 64 s de teste. O ponto 4 verifica em vez de supor o padrão `ignoreMigrationPatterns = *:future` do Flyway: o log da aplicação anterior mostra `Successfully validated 14 migrations`, `Schema "public" has a version (14) that is newer than the latest available migration (13) !` e `Schema "public" is up to date. No migration necessary.`.

## Limites

- A coleta incremental é o caminho de escrita exercitado pela release anterior; o backfill por `/ops/backfill` usa o mesmo `PageProcessor` e o mesmo repositório, então o resultado vale para ele, mas não foi rodado separadamente.
- Medido numa partição de 100 000 linhas, não numa tabela de milhões; ver o fim de `vacuum-bloat.md`.
