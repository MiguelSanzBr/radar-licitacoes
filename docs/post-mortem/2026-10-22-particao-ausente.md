# Post-mortem: partição do mês seguinte ausente em `ingest.procurement`

- Data da simulação: 2026-10-22 (22:36 em America/Sao_Paulo; os horários abaixo estão em UTC, 2026-10-23)
- Severidade: alta (a coleta de todo um mês falha), sem perda de dado
- Formato: sem culpados. O foco é o que o sistema permitiu, não quem esteve de plantão.
- Tipo: incidente simulado de verdade no compose (projeto `radar-incident`), com as saídas abaixo copiadas da execução.

## Resumo

A tabela `ingest.procurement` é particionada por mês, e a migration V2 só cria partições até 2027-12. Não existe partição `DEFAULT` (decisão do ADR-0010: falhar alto em vez de esconder o esquecimento) e nenhum processo criava as partições seguintes. Quando chega um edital publicado em 2028-01, o insert falha, a página inteira volta e a execução termina `FAILED`. Nenhuma métrica se move: o outbox continua vazio e `outbox_oldest_age_seconds` fica em 0. Só o log e a linha `FAILED` em `ingest.ingestion_run` denunciam. Na simulação, a correção manual levou segundos e a recarga do dia levou 29 s. A correção definitiva é um job diário que cria os meses à frente, um gauge `ingest_partitions_ahead` e o alerta `ParticaoFutura`, que dispara quando falta a partição do mês seguinte, até um mês antes da primeira falha.

## Como foi simulado

- Compose com o projeto `radar-incident` e a imagem construída da `main` antes da correção (último PR: `feat(search): fts query`). Keycloak, Kafka, Postgres 18, `otel-lgtm` e a aplicação reais; só o PNCP é a fixture do WireMock.
- Como as partições existem até 2027-12, a fixture do dia gravado (2026-10-21, modalidade 6, 1.633 editais) foi deslocada em 451 dias: todas as datas do arquivo somam 451 dias, então 2026-10-21 vira 2028-01-15 e a ordem entre publicação, abertura e encerramento se mantém. O deslocamento é só da simulação; a aplicação e o banco não foram alterados.
- A execução registrada abaixo vem de um volume novo. As tentativas anteriores foram descartadas porque a primeira versão do deslocamento gerou datas de proposta incoerentes e o backfill falhou por outro motivo (validação do edital, não partição).
- A recuperação foi feita no mesmo ambiente; a verificação da correção definitiva usou uma imagem construída do código desta correção.

## Impacto (medido na simulação)

| Medida | Valor |
|---|---|
| Execução do backfill (`ingestion_run` id 2) | `FAILED` em 0,46 s (01:36:05,134 até 01:36:05,594) |
| Editais buscados no PNCP antes da falha | 50 (primeira página) |
| Editais gravados em `ingest.procurement` | 0 de 1.633 do dia |
| Linhas em `outbox.outbox_event` | 0 |
| `outbox_pending`, `outbox_oldest_age_seconds` | 0 e 0,0 (nenhum sinal) |
| `ingest_items_total{kind="backfill"}` | ausente (nenhum item confirmado) |
| `pncp_items_fetched_total{endpoint="publicacao"}` | 50 |
| Perda de dado | nenhuma: `cursor_page` ficou em 0 e a mesma janela foi retomada depois |
| Efeito para o usuário | busca sem os editais do dia; nenhum alerta enviado para eles |

O que a simulação não mediu: a coleta incremental a cada 5 minutos também falharia para editais de 2028-01, e o código registra um aviso por modalidade e deixa o marcador onde estava (`IncrementalSyncUseCase`, `ScheduledIncrementalSync`), então nada se perde. Isso é leitura do código, não medição.

## Linha do tempo (UTC)

| Hora | Evento |
|---|---|
| 01:35:59 | Aplicação sobe; a coleta incremental inicial termina `DONE` sem itens (fixture sem dados na janela). |
| 01:36:03 | Linha de base: 48 partições, a última `procurement_y2027m12`; 0 editais, 0 linhas no outbox. |
| 01:36:04 | Operador chama `POST /ops/backfill?from=2028-01-15&to=2028-01-15&modality=6&force=true`: HTTP 202. |
| 01:36:05,554 | Primeiro sinal: `WARN HHH000247: ErrorCode: 0, SQLState: 23514` (Hibernate). |
| 01:36:05,557 | `WARN ERROR: no partition of relation "procurement" found for row` com `Detail: Partition key of the failing row contains (published_at) = (2028-01-15 03:00:03+00)`. |
| 01:36:05,594 | Execução 2 gravada como `FAILED` (`DataIntegrityViolationException: could not execute statement`). |
| 01:36:05,607 | `ERROR backfill 2028-01-15 to 2028-01-15 (modality 6) stopped` (`BackfillController`). |
| 01:36:13 | Medição do impacto (tabela acima); `to_regclass('ingest.procurement_y2028m01')` devolve nulo. |
| 01:36:13 | Correção manual: `SELECT ingest.ensure_partition('2028-01-01')` devolve `ingest.procurement_y2028m01` (limites 2028-01-01 a 2028-02-01 UTC). |
| 01:36:13 | Mesma chamada de backfill de novo: a execução `FAILED` é retomada (mesmo id 2), HTTP 202. |
| 01:36:42,196 | Execução 2 termina `DONE`: 33 páginas, 1.633 buscados e descobertos. |
| 01:36:58 | Verificação: 1.633 editais, 1.633 linhas no outbox, todas publicadas; `outbox_pending` 0; `ingest_items_total{backfill,discovered}` 1.633. |

Tempo entre a falha e a correção manual: cerca de 8 s na simulação, com o operador já olhando. Num incidente real o relógio começa quando o primeiro edital do mês chega, e sem alerta ninguém olha (ver Detecção).

## Causa raiz

1. O esquema criou partições por 48 meses e delegou a criação das seguintes a um "job mensal" que só existia como promessa no ADR-0010. Nada no sistema o executava.
2. A ausência de partição `DEFAULT` é intencional e continua sendo a escolha certa: um `DEFAULT` absorveria dados sem limite. Mas "falhar alto" só funciona se alguém ouve. A falha ocorre dentro da transação da página, antes de qualquer efeito observável (outbox, contadores), então os alertas de frescor que existem (`OutboxOldestAgeHigh`, `ProjectionLagHigh`, `RadarSemTrafego`) seguem verdes: eles medem o que anda devagar, não o que parou de entrar.
3. O prazo de 2027-12 parecia distante, e nenhum teste ou alarme contava os meses restantes.

Fatores que ajudaram: a escolha de uma transação por página manteve o banco consistente (nada gravado pela metade) e o marcador da execução não avançou, o que tornou a recuperação uma simples repetição da chamada.

## Detecção

- Antes: só o log `ERROR backfill ... stopped` e a linha `FAILED` em `ingest.ingestion_run`. Nenhuma métrica, nenhum alerta. Quem não lê o log não sabe.
- Agora: `ingest_partitions_ahead` (gauge lido no banco a cada scrape) e o alerta `ParticaoFutura` (`ingest_partitions_ahead < 1` por 1 h, crítico, runbook `particao-ausente`). O alerta dispara quando falta a partição do mês seguinte, ou seja, até um mês antes de o primeiro insert falhar. Com o job mantendo 3 meses à frente, esse estado só aparece depois de cerca de 3 meses de job quebrado, então o aviso chega com folga. O gauge cobre também o mês atual (vale 0 se ele faltar). A partida da aplicação cria as partições e só registra `ERROR` se, depois disso, o mês atual ou o seguinte ainda não existir (por exemplo, falta de permissão).

## Correção

1. Imediata (runbook, seção `particao-ausente`): `SELECT ingest.ensure_partition(...)` para o mês faltante e repetir o backfill da janela que falhou.
2. Definitiva: `ScheduledPartitionMaintenance` roda na partida e todo dia às 03:15 UTC e chama `ingest.ensure_partition` para o mês atual e os 3 seguintes (`radar.ingest.partitions.months-ahead`), com métrica, alerta e verificação. É idempotente e serializada por advisory lock (duas réplicas ao mesmo tempo não disputam o `CREATE TABLE`; um teste com duas chamadas concorrentes reproduziu `relation already exists` antes do lock).

Verificação da correção definitiva, no mesmo compose com a imagem construída do código final. O gauge é lido do catálogo a cada scrape, então mostra a partição derrubada na hora; a partida repara.

| Hora | Evento |
|---|---|
| 02:18:07 | `DROP TABLE ingest.procurement_y2026m11` (partição do mês seguinte, vazia): `to_regclass` devolve nulo. |
| 02:18:07 | `ingest_partitions_ahead{application="radar"} 0.0` com a aplicação no ar (o alerta contaria 1 h a partir daqui). |
| 02:18:08 | Reinício da aplicação com a imagem nova. |
| 02:18:19,275 | Partida: cria as partições e registra `INFO partition check: 3 months ahead`. |
| 02:18:22 | `to_regclass('ingest.procurement_y2026m11')` existe de novo; `ingest_partitions_ahead 3.0`. |

Uma verificação anterior, com a primeira versão do job (a partida só checava, sem criar), registrou `ERROR the partition of the next month ... does not exist` na partida e a recuperação pelo cron; o ERROR continua existindo para o caso de a criação falhar.

## O que funcionou e o que deu sorte

- Funcionou: a retomada da execução `FAILED` pelo mesmo id; a transação por página; o erro do Postgres, que diz a chave e o mês exatos.
- Sorte: o incidente é simulado e a data é de 2028. Num caso real, o primeiro sinal seria o usuário perceber editais faltando.

## Ações

Dono de todas: time. Não há remoto, então a referência é o título do PR.

| Ação | Estado | Onde |
|---|---|---|
| Job de manutenção das partições (partida e diário), gauge `ingest_partitions_ahead`, `ensure_partition` serializada por advisory lock entre réplicas | feita | PR 4.5 `feat(ingest): monthly partition job, partitions-ahead gauge and ParticaoFutura alert` e `feat(ingest): serialize ensure_partition, ensure at startup and cover the current month` |
| Teste que falha se a partição do mês seguinte não existir, e teste de duas chamadas concorrentes | feita | PR 4.5 `test(ingest): monthly partition job, partitions-ahead gauge and ParticaoFutura alert` e `test(ingest): concurrent ensure, startup ensure, current month and image fails fast without the read-writer password` |
| Alerta `ParticaoFutura` com teste de regra (`promtool`) | feita | PR 4.5 (mesmos commits `feat` e `test` do job; `slo.yml`, `slo_test.yml`) |
| Seção `particao-ausente` no runbook e verificação das âncoras em `make lint-observability` | feita | PR 4.5 `docs(ops): runbook, 12-factor audit, incident and post-mortem` |
| `make backfill` sem token (recebia 401 desde que `/ops` exige o papel `operator`) | feita | PR 4.5 `feat(ops): image fails fast without the read-writer password and ops token script` (`scripts/ops-token.sh`) |
| Alertar execução de ingestão `FAILED` (hoje só há a linha no banco e o log): expor uma métrica por desfecho de execução | aberta | próximo PR de observabilidade da coleta |
