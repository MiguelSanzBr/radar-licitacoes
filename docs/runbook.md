# Runbook de operação

Cada alerta de `infra/prometheus/rules/slo.yml` aponta para uma seção deste arquivo pelo campo `runbook_url`. `make lint-observability` falha se uma âncora citada pelas regras não existir aqui. Os comandos foram rodados contra o compose (`make up`); em Kubernetes, troque `$COMPOSE exec` por `kubectl exec`.

## Convenções

```
COMPOSE="docker compose -f infra/compose/docker-compose.yml"
PSQL="$COMPOSE exec postgres psql -U radar -d radar"
```

- Logs da aplicação: `make logs SERVICE=app` (JSON com `traceId`, `correlationId`, `level`). Para filtrar sem ficar preso no `-f`: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep '"level":"ERROR"'`.
- Métricas: Grafana em http://localhost:3000 (`admin` / `admin`), pasta dos dashboards "Radar". No compose a aplicação envia métricas por OTLP; as regras de `slo.yml` só são avaliadas onde há Prometheus carregando o arquivo (Kubernetes), então no compose use os dashboards e as consultas abaixo como equivalente.
- Painel de saúde: `/ops/painel` (página, entra com a conta de operador) e `GET /ops/health-panel` (JSON, token de operador) mostram a fila de saída, a última coleta de cada modalidade, o atraso dos consumidores, as mensagens sem reprocessar na DLQ, o ponto de retomada da projeção e o estado das flags. Cada componente sai `UP`, `DEGRADED` ou `DOWN`; a situação geral é a pior delas. As consultas SQL desta página servem para o detalhe.
- Token de operador (para `/ops/*` e `/actuator/prometheus`):

```
TOKEN=$(scripts/ops-token.sh)
curl -fsS -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/prometheus | grep -E '^(outbox_|ingest_)'
```

- Escalonamento: o "time" é o dono do serviço. Alerta `critical` exige resposta imediata e abre incidente (linha do tempo e post-mortem sem culpados em `docs/post-mortem/`); `warning` entra na fila do dia. Quando o diagnóstico não fecha em 30 minutos, ou há perda possível de dado, escale para o time e registre o que já foi descartado.

| Alerta | Severidade | Seção |
|---|---|---|
| `OutboxOldestAgeHigh` | warning | [outbox-atrasado](#outbox-atrasado) |
| `OutboxReplicationSlotLagHigh`, `OutboxReplicationSlotInactive` | warning | [slot-do-cdc-atrasado](#slot-do-cdc-atrasado) |
| `ProjectionLagHigh` | warning | [projecao-atrasada](#projecao-atrasada) |
| `SearchLatencyFastBurn`, `SearchLatencySlowBurn` | critical, warning | [busca-lenta](#busca-lenta) |
| `SearchErrorBudgetFastBurn`, `SearchErrorBudgetSlowBurn` | critical, warning | [busca-indisponivel](#busca-indisponivel) |
| `AlertDeliveryLatencyHigh` | warning | [alerta-atrasado](#alerta-atrasado) |
| `RadarTargetDown` | critical | [alvo-fora](#alvo-fora) |
| `RadarSemTrafego` | warning | [sem-trafego](#sem-trafego) |
| `ParticaoFutura` | critical | [particao-ausente](#particao-ausente) |
| `PncpBloqueado`, `PncpBloqueadoPorMuito` | warning, critical | [pncp-bloqueado](#pncp-bloqueado) |

<a id="outbox-atrasado"></a>

## Outbox atrasado (`OutboxOldestAgeHigh`)

**Sintoma.** `outbox_oldest_age_seconds` acima de 5 s por 10 minutos: eventos gravados pela coleta não chegam ao Kafka.

**Impacto.** A busca e os alertas ficam defasados. Não há perda: as linhas continuam em `outbox.outbox_event` e o relay as publica quando o obstáculo some (ADR-0018). Com `radar.outbox.mode=cdc` não há relay e estas métricas ficam em zero: veja [slot-do-cdc-atrasado](#slot-do-cdc-atrasado).

**Diagnóstico.**

1. Dashboard "Radar: outbox e Kafka": painéis "Mensagens pendentes no outbox", "Idade da mensagem mais antiga no outbox" e "Poison messages por segundo".
2. Estado da fila:

```
$PSQL -c "SELECT count(*) FILTER (WHERE published_at IS NULL) AS pendentes,
  max(now() - created_at) FILTER (WHERE published_at IS NULL) AS mais_antiga,
  count(*) FILTER (WHERE published_at IS NULL AND attempts >= 10) AS envenenadas
  FROM outbox.outbox_event"
$PSQL -c "SELECT id, aggregate_id, attempts, left(last_error, 200) AS erro
  FROM outbox.outbox_event WHERE published_at IS NULL ORDER BY id LIMIT 5"
```

3. Broker: `$COMPOSE ps kafka` e `$COMPOSE exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`.
4. Logs do relay: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep -i outbox`.
5. Relay desligado por configuração: `radar.outbox.relay-enabled=false` (variável `RADAR_OUTBOX_RELAY_ENABLED`) mantém o app no ar sem publicar.

**Ação.**

- Broker fora: suba (`$COMPOSE up -d kafka`). O relay retoma sozinho, sem contar tentativa por queda do broker (`docs/benchmarks/outbox/broker-down.md`).
- Poison (`attempts >= 10`): leia `last_error`, corrija a causa e libere a linha com `UPDATE outbox.outbox_event SET attempts = 0 WHERE id = <id>`. A fila dos outros agregados não é bloqueada enquanto isso.
- Relay desligado por engano: religue e reinicie o app.

**Escalonamento.** Idade acima de 30 minutos, `outbox_poison_pending` maior que 0 ou causa não identificada: time do outbox.

<a id="slot-do-cdc-atrasado"></a>

## Slot do CDC atrasado (`OutboxReplicationSlotLagHigh`, `OutboxReplicationSlotInactive`)

Só vale com `radar.outbox.mode=cdc` (ADR-0018). No modo `polling` as métricas do slot nem existem.

**Sintoma.** `outbox_replication_slot_lag_bytes` acima de 256 MiB por 10 minutos ou igual a `-1` (slot ausente), ou `outbox_replication_slot_active` igual a 0 por 5 minutos (ninguém lê o slot: conector parado). Nesse modo `outbox_pending` e `outbox_oldest_age_seconds` ficam em zero de propósito: o atraso é o do slot.

**Impacto.** Os eventos esperam o Debezium, então a busca e os alertas ficam defasados. Não se perde evento enquanto o slot existe: ele segura o WAL do `INSERT` até o conector confirmar, e a limpeza do outbox (por `created_at`, 7 dias) não interfere nisso. O risco real é o slot ser invalidado: o Postgres do compose invalida o slot ao passar de 1 GB de WAL retido (`max_slot_wal_keep_size`), e o que foi gravado depois disso, ou ainda não lido na invalidação, não sai pelo CDC. Um slot parado também enche o disco do Postgres até esse limite.

**Diagnóstico.**

1. Estado do slot (`active = f` com `lag` crescendo é conector parado; `wal_status = lost` é slot invalidado):

```
$PSQL -c "SELECT slot_name, active, wal_status,
  pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS lag
  FROM pg_replication_slots"
```

2. Conector: `$COMPOSE exec debezium curl -s localhost:8083/connectors/radar-outbox/status` (o `state` das tarefas deve ser `RUNNING`) e `$COMPOSE logs --no-log-prefix --tail=200 debezium`.
3. Broker fora ou conector sem acesso ao Kafka: o log do conector traz timeouts do produtor.
4. Com o outbox parado e outras tabelas recebendo escrita, o lag deve cair sozinho em até uns 20 segundos: o conector escreve um batimento a cada 10 s em `outbox.cdc_heartbeat` (publicada) e é isso que faz o slot avançar. Medido: 61 MB de WAL de outra tabela foram confirmados em cerca de 16 s; só com `heartbeat.interval.ms`, sem a escrita, o lag ficou em 64 MB por mais de 30 s. Se o lag não cai, veja se a tabela existe (`enable-cdc.sql` a cria) e o log do conector.

**Ação.**

- Conector parado ou caído: `$COMPOSE up -d debezium`. Ele retoma do ponto confirmado no slot e o lag cai sozinho. Eventos enviados e ainda não confirmados saem de novo (ao menos uma vez), e o Inbox dos consumidores absorve.
- Slot ausente ou `wal_status = lost`: o conector não retoma, o log dele diz `Last recorded offset is no longer available on the server`. Entregue o que faltou voltando para `polling` pelo caminho de [trocar o modo do outbox](#trocar-o-modo-do-outbox), **pulando o passo 2 da volta** (não marque nada como publicado: o conector não está saudável e o que ele entregou não é confiável). Rode só parar o conector, `disable-cdc.sql`, apagar os offsets e reiniciar o app em `polling` (passos 3 e 4 da volta): o relay republica toda a tabela retida (7 dias) e os Inboxes dos consumidores absorvem a duplicata. Depois, se quiser o CDC de novo, siga os passos para `cdc`.
- Slot de um conector aposentado: um slot sem conector retém WAL para sempre. Apague com `$PSQL -c "SELECT pg_drop_replication_slot('radar_outbox')"` só depois de parar o conector e de confirmar que nada depende dele.
- Tópico `__debezium-heartbeat.radar`: é o batimento do conector, pequeno, e nenhum consumidor da aplicação o lê.

**Escalonamento.** Slot invalidado, disco do Postgres acima de 80% ou lag que não cai com o conector `RUNNING`: time do outbox.

<a id="projecao-atrasada"></a>

## Projeção atrasada (`ProjectionLagHigh`)

**Sintoma.** p95 de `projection_lag_seconds` acima de 30 s por 10 minutos: o evento saiu do outbox, mas a visão de busca demora a refleti-lo.

**Impacto.** Editais novos ou alterados aparecem tarde na busca e no casamento com buscas salvas. A visão é reconstruível a partir dos eventos.

**Diagnóstico.**

1. Descarte a montante: se o outbox está atrasado, trate [outbox-atrasado](#outbox-atrasado) primeiro.
2. Lag do consumidor:

```
$COMPOSE exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group search-projector
$PSQL -c "SELECT * FROM search.projection_checkpoint"
```

3. Erros do projetor: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep -E 'ProcurementEventsListener|search'`. Queda do banco (conexão, tempo esgotado, trava) é tentada a cada 5 s sem limite e nunca vai para a DLQ; registro que nunca valida, ou que o banco rejeita (valor grande demais, por exemplo), vai para `radar.procurement.v1.dlq` e aparece no painel (veja "Reprocessar a DLQ").
4. Carga concorrente: um backfill histórico gera muitos eventos de uma vez e o lag sobe sem defeito. Confira: `$PSQL -c "SELECT id, kind, status, window_start::date FROM ingest.ingestion_run WHERE status = 'RUNNING'"`.
5. Projetor desligado: `radar.search.projector.enabled=false`.
6. Projetor pausado pelo backpressure (ADR-0026): o gauge `search_projector_paused` em 1 (uma pausa dura `radar.search.projector.backpressure.pause-for`, 10 s por padrão) significa que o tempo médio de projeção passou do limite; olhe o banco (`search_projector_record_seconds` mostra o tempo por registro) e trate a lentidão dele. Pausas repetidas com o banco saudável pedem ajuste do limite `slow-after`.

**Ação.** Backfill em andamento: aguarde. Banco indisponível: restaure o Postgres, o projetor recupera sozinho. Registros na DLQ: corrija a causa e reprocesse pelo endpoint (veja "Reprocessar a DLQ").

**Escalonamento.** Lag crescendo por mais de 30 minutos sem backfill: time de busca.

<a id="busca-lenta"></a>

## Busca lenta (`SearchLatencyFastBurn`, `SearchLatencySlowBurn`)

**Sintoma.** Menos de 95% das respostas de `GET /api/v1/procurements` abaixo de 200 ms (queima rápida: 1 h e 5 min; lenta: 6 h e 30 min).

**Impacto.** Usuários da busca percebem lentidão. Sem perda de dado.

**Diagnóstico.**

1. Dashboard "Radar: busca e API": "Latência p95 por rota", "Latência p99 por rota", "Requisições por segundo por rota".
2. Consultas mais custosas no Postgres:

```
$PSQL -c "SELECT calls, round(mean_exec_time::numeric, 1) AS media_ms, left(query, 100) AS consulta
  FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10"
```

3. Concorrência com carga de escrita: backfill em andamento (consulta do passo 4 de [projecao-atrasada](#projecao-atrasada)) e `$PSQL -c "SELECT state, count(*) FROM pg_stat_activity GROUP BY 1"`.
4. Estatísticas velhas depois de carga em massa: `$PSQL -c "ANALYZE search.procurement_search_view"`.
5. Memória do container: `docker stats --no-stream`.

**Ação.** Rode `ANALYZE` se a carga acabou de terminar. Backfill competindo: espere terminar (ele retoma da página não confirmada se o app reiniciar) ou rode-o na janela noturna. Consulta nova lenta: guarde o `EXPLAIN (ANALYZE, BUFFERS)` e abra a correção de índice.

**Escalonamento.** Queima rápida por mais de 30 minutos: time de busca.

<a id="busca-indisponivel"></a>

## Busca indisponível (`SearchErrorBudgetFastBurn`, `SearchErrorBudgetSlowBurn`)

**Sintoma.** Respostas 5xx de `GET /api/v1/procurements` consumindo o orçamento de erro (alvo 99,5%) 14,4 vezes (1 h e 5 min) ou 6 vezes (6 h e 30 min) mais rápido que o aceito.

**Impacto.** Parte das buscas falha. Os dados continuam íntegros.

**Diagnóstico.**

1. Dashboard "Radar: busca e API": "Taxa de 5xx por rota".
2. Prontidão e erros: `curl -fsS localhost:8080/actuator/health/readiness` e `$COMPOSE logs --no-log-prefix --tail=1000 app | grep '"level":"ERROR"'`.
3. A busca lê com o papel `radar_read_writer`. Erro `password authentication failed for user "radar_read_writer"` indica senha desalinhada (veja "Rotação de segredos").
4. Postgres: `$COMPOSE ps postgres` e `$PSQL -c "SELECT count(*) FROM pg_stat_activity"` (o limite é 100 conexões).

**Ação.** Postgres fora: suba e confirme a prontidão. Senha desalinhada: alinhe o papel e o segredo e reinicie o app. Erro após deploy: volte à imagem anterior com o [rollback](#rollback).

**Escalonamento.** Queima rápida sem causa em 15 minutos: time dono do serviço.

<a id="alerta-atrasado"></a>

## Alerta atrasado (`AlertDeliveryLatencyHigh`)

**Sintoma.** p95 de `alert_delivery_latency_seconds` acima de 60 s por 10 minutos entre a projeção e o e-mail aceito pelo SMTP. A métrica entra com o PR 3.3 (notificador de e-mail).

**Impacto.** Assinantes recebem o aviso de um edital novo com atraso.

**Diagnóstico.**

1. Dashboard "Radar: alertas": "Latência de entrega p95" e "Mensagens na DLQ por segundo por tópico".
2. Casamentos sem envio:

```
$PSQL -c "SELECT count(*) AS pendentes, min(matched_at) AS mais_antigo FROM alerts.alert_delivery WHERE sent_at IS NULL"
$COMPOSE exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group alerts-matcher
```

3. SMTP: no compose, http://localhost:8025 (Mailpit) mostra o que chegou; `$COMPOSE ps mailpit`.
4. Logs: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep -i alert`.

**Ação.** Matcher atrasado: siga [projecao-atrasada](#projecao-atrasada) para o grupo `alerts-matcher`. SMTP fora: suba o serviço; o envio é tentado de novo. Um backfill histórico gera casamentos que não deveriam virar aviso: o matcher ignora evento mais velho que `radar.alerts.max-event-age` (1 dia) e pode ser desligado com `radar.alerts.matcher.enabled=false` durante a carga.

**Escalonamento.** Pendentes crescendo por mais de 30 minutos: time de alertas.

<a id="outbox-envenenado"></a>

## Outbox envenenado (`OutboxPoisonPending`)

**Sintoma.** `outbox_poison_pending` maior que 0 por 5 minutos: há linhas em `outbox.outbox_event` que o relay tentou `radar.outbox.max-attempts` vezes (10) e largou.

**Impacto.** O evento daquele agregado não chega ao Kafka: a busca e os alertas ficam sem ele até alguém liberar a linha. A fila dos outros agregados anda, por isso `OutboxOldestAgeHigh` pode não disparar. Não há perda, a linha continua no banco.

**Diagnóstico.**

1. Dashboard "Radar: outbox e Kafka", painel "Poison messages por segundo".
2. As linhas e a causa:

```
$PSQL -c "SELECT id, aggregate_id, event_type, attempts, left(last_error, 200) AS erro
  FROM outbox.outbox_event WHERE published_at IS NULL AND attempts >= 10 ORDER BY id"
```

**Ação.** Corrija a causa que o `last_error` mostra (schema recusado, payload inválido, tópico ausente) e reimplante se for código. Depois libere as linhas: `UPDATE outbox.outbox_event SET attempts = 0 WHERE published_at IS NULL AND attempts >= 10`. O relay as publica no próximo ciclo. Se a causa é o schema, veja "Schema recusado" em "Trocar o formato dos eventos entre JSON e Avro".

**Escalonamento.** A causa não é clara ou as linhas voltam a ficar poison depois de liberadas: time do outbox.

<a id="mensagens-na-dlq"></a>

## Mensagens na DLQ (`DlqMessages`)

**Sintoma.** `dlq_messages_total` aumentou nos últimos 15 minutos: um consumidor desistiu de um registro e o enviou para `radar.procurement.v1.dlq` ou `radar.alert.v1.dlq`. O rótulo `topic` diz qual.

**Impacto.** O registro não foi projetado (busca) ou casado (alertas), ou o e-mail não saiu. Nada se perde: a mensagem está no tópico da DLQ e em `ops.dlq_message`. O alerta dispara uma vez por rajada: depois de 15 minutos sem registro novo ele se resolve, mas as mensagens seguem pendentes até o reprocessamento.

**Diagnóstico.**

1. Painel de saúde (`/ops/painel`), linha "Fila de mensagens mortas", ou o dashboard "Radar: alertas" ("Mensagens na DLQ por segundo por tópico").
2. As mensagens pendentes e a causa:

```
$PSQL -c "SELECT id, topic, consumer_group, error_class, left(error_message, 80) AS erro, recorded_at FROM ops.dlq_message WHERE reprocessed_at IS NULL ORDER BY id"
```

**Ação.** Corrija a causa e reenvie as mensagens como em "Reprocessar a DLQ" (seção de rotinas). Uma mensagem malformada volta à DLQ: nesse caso corrija o dado ou o código antes.

**Escalonamento.** Muitas mensagens em pouco tempo ou o mesmo `error_class` em todas: time dono do consumidor (`consumer_group`).

<a id="alvo-fora"></a>

## Alvo fora (`RadarTargetDown`)

**Sintoma.** `up{job="radar"} == 0` por 2 minutos: o Prometheus não consegue ler `/actuator/prometheus`. Só existe onde há scrape (Kubernetes); no compose a aplicação envia por OTLP e a ausência de dados aparece em [sem-trafego](#sem-trafego).

**Impacto.** Sem métricas e sem os demais alertas. O app pode estar fora do ar ou só inacessível ao scrape.

**Diagnóstico.**

1. Processo e saúde: `$COMPOSE ps app`, `curl -fsS localhost:8080/actuator/health/liveness`.
2. Morte por memória: `docker inspect -f '{{.State.OOMKilled}} {{.State.ExitCode}}' $($COMPOSE ps -q app)`.
3. Últimos logs: `make logs SERVICE=app`.
4. Scrape com 401: o endpoint exige o papel `operator`; o token montado no Prometheus expirou (ADR-0019).

**Ação.** Reinicie (`$COMPOSE restart app`) se o processo caiu; renove o token se foi 401. Reinícios repetidos: veja o último erro de partida (migration, banco, Kafka).

**Escalonamento.** Alvo fora por mais de 5 minutos: time dono do serviço, é incidente.

<a id="sem-trafego"></a>

## Sem tráfego (`RadarSemTrafego`)

**Sintoma.** Nenhuma requisição a `/api/v1/procurements` registrada por 15 minutos.

**Impacto.** Pode ser silêncio normal de madrugada ou defeito entre o usuário e a aplicação (DNS, ingress, login).

**Diagnóstico.**

1. A aplicação responde? `curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/procurements` deve dar 200 (404 enquanto a API de busca não estiver publicada, e o alerta só faz sentido depois dela).
2. Se o alerta apareceu logo depois de um reinício, a série só volta com a primeira requisição.
3. Keycloak e rede: `$COMPOSE ps keycloak`; a busca é pública, mas a página depende do login.

**Ação.** Resposta 200 e horário de baixo uso: nada a fazer. Resposta diferente de 200: trate como [busca-indisponivel](#busca-indisponivel). Falha só de fora: verifique ingress e DNS.

**Escalonamento.** Aviso que persiste em horário comercial: time dono do serviço.

<a id="particao-ausente"></a>

## Partição ausente (`ParticaoFutura`)

**Sintoma.** `ingest_partitions_ahead < 1` por 1 hora: o mês atual ou o seguinte não tem partição em `ingest.procurement`. O log de partida registra `ERROR the partition of the current or the next month of ingest.procurement does not exist` (a partida já tenta criar as partições antes de checar, então esse erro indica que a criação falhou). Na virada do mês, o backfill ou a coleta incremental termina com `FAILED` e o erro `no partition of relation "procurement" found for row`.

**Impacto.** A tabela não tem partição DEFAULT de propósito: o insert de um edital publicado no mês sem partição falha, a página inteira volta (uma transação por página) e a execução fica `FAILED`. Não há perda: o marcador da coleta não avança e o outbox fica vazio, então nenhuma outra métrica alerta. Sem o `ParticaoFutura`, só o log denuncia (post-mortem de 2026-10-22).

**Diagnóstico.**

1. Partições existentes e execuções com falha:

```
$PSQL -c "SELECT c.relname, pg_get_expr(c.relpartbound, c.oid) AS limites
  FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
  WHERE i.inhparent = 'ingest.procurement'::regclass ORDER BY 1 DESC LIMIT 6"
$PSQL -c "SELECT id, kind, status, window_start::date, left(error, 80) AS erro
  FROM ingest.ingestion_run WHERE status = 'FAILED' ORDER BY id DESC LIMIT 5"
```

2. O job diário falhou? `$COMPOSE logs --no-log-prefix --tail=1000 app | grep -i partition` (procure `partition maintenance failed` e a causa, por exemplo permissão no schema `ingest`).
3. Job desligado: `radar.ingest.partitions.enabled=false`.

**Ação.**

1. Crie as partições agora (idempotente, limites em UTC):

```
$PSQL -c "SELECT ingest.ensure_partition((date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => m))::date)
  FROM generate_series(0, 3) AS m"
```

2. Refaça a janela que falhou. A execução `FAILED` retoma da página não confirmada:

```
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/ops/backfill?from=AAAA-MM-DD&to=AAAA-MM-DD&modality=6&force=true"
```

3. Verifique: `ingest_partitions_ahead` volta ao valor de `radar.ingest.partitions.months-ahead` (3), a execução termina `DONE` e `outbox.outbox_event` volta a crescer.
4. Corrija a causa do job (permissão, flag, agendamento) para a próxima virada.

**Escalonamento.** Execução que continua `FAILED` depois das partições criadas: time de coleta (a causa é outra).

<a id="pncp-bloqueado"></a>

## PNCP bloqueou o cliente (`PncpBloqueado`, `PncpBloqueadoPorMuito`)

**Sintoma.** `pncp_blocked_until_seconds > 0`: o PNCP respondeu 429 ou 403 e o cliente parou de chamar até o instante (em segundos desde 1970) que a métrica mostra. `PncpBloqueado` (aviso) dispara na hora; `PncpBloqueadoPorMuito` (crítico) quando o bloqueio dura mais de 2 h seguidas, o que indica que a pausa não está bastando. O contador `pncp_blocked_total{status}` conta as execuções que pararam por causa do bloqueio (não os ciclos pulados depois dele). O log traz um WARN `PNCP blocked the client (HTTP 429), no calls until ..., response: <início do corpo>` na resposta que bloqueou e um WARN `... skipped: PNCP blocked the client until ...` por bloqueio, na coleta incremental e no backfill agendado.

**Impacto.** Sem coleta nova enquanto durar a pausa. Não há perda: só a execução que tomou o 429 fica `FAILED` (com o marcador de página onde estava; os ciclos seguintes nem criam execução) e a coleta incremental não avança o marcador, então o que passou na pausa é lido depois. O cliente não faz nenhuma requisição enquanto bloqueado (a chamada falha na hora), e é de propósito: a API devolveu uma página de WAF, e insistir pode alongar o bloqueio. A pausa dura o `Retry-After` do PNCP, quando vem (segundos ou data HTTP, no máximo 24 h e nunca menos que o intervalo do limitador de taxa), ou `radar.pncp.block-pause` (padrão `PT30M`, mínimo 1 minuto). **O estado da pausa fica na memória de cada instância:** reiniciar a aplicação ou ter uma segunda instância faz sair mais uma requisição ao PNCP, que pode levar outro 429.

**Diagnóstico.**

1. Até quando e por qual status:

```
curl -fsS -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/prometheus | grep -E '^pncp_blocked'
date -d @<valor de pncp_blocked_until_seconds>
```

2. Execuções paradas e o erro registrado:

```
$PSQL -c "SELECT id, kind, status, window_start::date, cursor_page, left(error, 90) AS erro
  FROM ingest.ingestion_run WHERE status = 'FAILED' ORDER BY id DESC LIMIT 5"
```

3. O que veio na resposta (corpo HTML de WAF, sem `Retry-After`, é o caso conhecido): `$COMPOSE logs --no-log-prefix --tail=1000 app | grep "PNCP blocked the client"`.
4. Volume pedido no dia: `pncp_request_duration_seconds_count` por `outcome` no dashboard "Radar: coleta". Veja se algo (backfill manual, `fetch-ahead` alto, mais de uma instância) passou da taxa de `radar.pncp.requests-per-second`.

**Ação.**

1. **Espere.** Não reinicie a aplicação para "limpar" o bloqueio: a pausa é só a memória da instância e o bloqueio é do PNCP, então reiniciar zera a pausa, a próxima requisição pode levar outro 429 e alongar o bloqueio.
2. **Não suba a taxa** (`radar.pncp.requests-per-second` fica em 0,2) nem o `radar.pncp.block-pause` para menos do padrão. Se o bloqueio voltar mesmo a 0,2 req/s, a taxa baixa e a mudança vira ADR (ADR-0017).
3. Confira o volume (passo 4 do diagnóstico) e pare o que estiver acima do esperado, por exemplo um backfill manual em paralelo com a coleta.
4. Depois que `pncp_blocked_until_seconds` voltar a 0, a coleta incremental retoma sozinha no próximo ciclo. Refaça os dias do backfill que pararam; a execução `FAILED` retoma da página não confirmada:

```
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/ops/backfill?from=AAAA-MM-DD&to=AAAA-MM-DD&modality=6&force=true"
```

5. Verifique: o alerta some, a execução termina `DONE` e `ingest_items_total` volta a crescer.

**Escalonamento.** Bloqueio que passa de 24 h, ou que volta a cada pausa: time de coleta (o PNCP pode ter uma regra nova; registre o que foi medido no ADR-0017).

## Operações rotineiras

### Trocar o formato dos eventos entre JSON e Avro

**Quando.** O padrão é JSON. Avro (ADR-0024) é opcional e pede o Apicurio no ar. Os consumidores leem os dois formatos sempre, então a troca só mexe no produtor.

**Ligar.** `PROFILE=layer3 make up`, depois `make schemas-register` (cria o grupo de cada tópico com a regra `BACKWARD`; pode rodar de novo) e reinicie a aplicação com `RADAR_MESSAGING_FORMAT=avro`. Confira em `GET http://localhost:8081/apis/registry/v3/groups/radar.procurement.v1/artifacts` que o primeiro evento registrou o artefato do seu tipo.

**Desligar.** `RADAR_MESSAGING_FORMAT=json` e reinicie. Os registros Avro que já estão nos tópicos continuam legíveis enquanto o registry tiver os schemas.

**Registry fora do ar.** Com `avro`, a publicação para: as linhas ficam no outbox sem gastar tentativa (`OutboxOldestAgeHigh` avisa) e voltam a sair quando o registry volta. O consumo de um registro Avro que o consumidor ainda não decodificou também espera: o registro é repetido a cada 5 s (`avro-registry-unavailable` no log) e não vai para a DLQ; cada chamada ao registry espera no máximo `RADAR_MESSAGING_REGISTRY_TIMEOUT_MS` (10 s), e depois da primeira falha o consumidor marca os registros Avro na hora, sem chamar o registry, por `RADAR_MESSAGING_REGISTRY_BACKOFF_MS` (5 s), para que uma leitura de centenas de registros não espere o prazo de cada um (um registro com schema em cache espera essa janela também). Se uma leitura ainda ficar lenta, reduza `SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS`. Se ele não voltar logo, desligue o Avro (acima) e o outbox drena em JSON.

**Registro Avro na DLQ.** A DLQ guarda o JSON do evento, em texto (nunca os bytes do Avro), então o reprocessamento da DLQ funciona como para JSON: republica o texto e o produtor o converte em Avro. Só um registro com o texto `avro-undecodable:` (schema que o registry não conhece, bytes inválidos) guarda os bytes em Base64 e não pode ser reprocessado; decodifique o Base64, descubra o schema pelo id (bytes 1 a 4, `GET /apis/ccompat/v7/schemas/ids/{id}`) e republique o evento pela origem.

**Schema recusado.** Um evento cujo schema quebra o `BACKWARD` (ou que o Avro não consegue codificar) é uma recusa, não uma queda: a publicação falha com `RuleViolationException` (ou o erro do Avro) no `last_error` da linha do outbox e cada tentativa conta. Depois de `radar.outbox.max-attempts` (10) a linha vira poison (`outbox_poison_total` sobe, `attempts >= 10`) e sai da fila, sem bloquear os outros agregados. Corrija o schema (campo novo opcional com padrão), reimplante e libere as linhas como na seção do outbox atrasado: `UPDATE outbox.outbox_event SET attempts = 0 WHERE published_at IS NULL AND attempts >= 10`.

**No modo `cdc`** os eventos continuam em JSON, qualquer que seja o formato.

### Redis fora do ar (limite abre, cache do detalhe lê o Postgres)

**Sintoma.** `rate_limit_fail_open_total` cresce e o log traz, no máximo uma vez por minuto, `WARN Rate limiter unavailable, public reads pass unmetered (fail-open)`. As respostas das rotas públicas ficam sem `RateLimit-*`. O cache do detalhe também falha aberto: `search_detail_cache_errors_total` cresce e o log traz, no máximo uma vez por minuto, `WARN Detail cache unavailable, reading from the database (fail-open)`.

**Impacto.** A leitura pública continua no ar, sem limite, e o detalhe sai do Postgres (ADR-0027). Enquanto o Redis estiver fora, uma invalidação do cache que não chegou deixa o detalhe antigo até o TTL de 1 h, se o Redis voltar com os dados (queda de rede, não reinício). Com o Redis recusando conexão, o cliente rejeita os comandos na hora e a leitura não espera. Com o Redis pendurado (sem responder), cada leitura espera até 250 ms (`spring.data.redis.timeout`) antes de seguir e a latência sobe nesse valor enquanto durar.

**Ação.** Suba o Redis (`$COMPOSE up -d redis`); o cliente reconecta sozinho. O estado dos baldes se perde sem prejuízo: balde ausente é balde cheio. Se a queda for longa e houver abuso, reduza a exposição na borda (proxy) ou desligue o limite com `RADAR_RATE_LIMIT_ENABLED=false`. Com o cache ligado, uma falha do Redis faz o cache pular o Redis por 10 s, então a espera de 250 ms com o Redis pendurado acontece uma vez por janela; se mesmo assim incomodar, desligue o cache com `RADAR_SEARCH_DETAIL_CACHE_ENABLED=false`. Se o Redis voltar com dados antigos e o detalhe estiver desatualizado, limpe o cache: `redis-cli --scan --pattern 'search:detail:v1:*' | xargs -r redis-cli unlink`.

**Cuidados.** O Redis precisa de `maxmemory-policy volatile-ttl` (toda chave tem TTL); com `noeviction` e a memória cheia o limite passa a abrir para todos (`rate_limit_fail_open_total` sobe com `OOM` no log). As chaves do cache do detalhe (`search:detail:v1:*`) também têm TTL. Em produção o log DEBUG de requisição e resposta deve ficar desligado: a resposta de `POST /api/v1/api-keys` leva a chave em claro.

### Trocar o modo do outbox

`radar.outbox.mode` (variável `RADAR_OUTBOX_MODE`) escolhe quem publica: `polling` (padrão, o relay do app) ou `cdc` (Debezium, ADR-0018). Nunca os dois ao mesmo tempo: um evento sairia duas vezes. A coluna `envelope` e a publicação do CDC não são do Flyway: `infra/cdc/enable-cdc.sql` as cria e `infra/cdc/disable-cdc.sql` as remove, os dois idempotentes. Exigem PostgreSQL 18 (o 17 recusa a publicação com coluna gerada; o script confere a versão antes de mexer em qualquer coisa) e `wal_level=logical`. `ADD COLUMN ... STORED` reescreve a tabela com `ACCESS EXCLUSIVE`, alguns segundos numa fila de 7 dias.

No compose, `PROFILE=cdc make up` num ambiente novo faz tudo: o serviço `cdc-setup` aplica o `enable-cdc.sql` depois que o app ficou saudável e antes do Debezium. As linhas que o app gravar antes do `cdc-setup` terminar não saem (não há relay nem slot ainda); num ambiente com dados, use os passos abaixo.

Para `cdc`:

1. Cheque as linhas envenenadas: `$PSQL -c "SELECT count(*) FROM outbox.outbox_event WHERE published_at IS NULL AND attempts >= 10"` deve dar 0. Linhas envenenadas nunca esvaziam pelo relay e o CDC não as vê (são anteriores ao slot): trate-as antes, como em [outbox-atrasado](#outbox-atrasado), ou aceite que ficam fora.
2. Aplique o script: `$PSQL -v ON_ERROR_STOP=1 -f - < infra/cdc/enable-cdc.sql` (no compose, o `cdc-setup` faz isto).
3. Crie o slot, para o WAL ficar retido a partir daqui (o conector usa o slot que já existe), e guarde o instante em seguida: `$PSQL -c "SELECT pg_create_logical_replication_slot('radar_outbox', 'pgoutput')"` e `T=$($PSQL -tAc "SELECT clock_timestamp()")`.
4. Antes de parar o relay, espere ele esvaziar as linhas anteriores a `T`, que o slot não vê: `$PSQL -tAc "SELECT count(*) FROM outbox.outbox_event WHERE created_at < '$T' AND published_at IS NULL AND attempts < 10"` deve dar 0. Uma linha com `created_at` anterior a `T` que só faz commit depois de `T` também está no slot e sai duas vezes (duplicata, absorvida pelo Inbox).
5. Reinicie o app com `RADAR_OUTBOX_MODE=cdc` (o relay sai).
6. Suba o conector: `$COMPOSE --profile cdc up -d debezium`.
7. Confira: o `state` da tarefa em `$COMPOSE exec debezium curl -s localhost:8083/connectors/radar-outbox/status` é `RUNNING` e `outbox_replication_slot_lag_bytes` fica perto de zero.

As linhas gravadas entre os passos 3 e 5 saem pelo relay e pelo conector (ao menos uma vez); o Inbox dos consumidores absorve a duplicata.

Para voltar a `polling` com o conector saudável (a ordem importa: marcar antes de parar o conector evita perder evento). Com o slot perdido ou invalidado, pule o passo 2 e vá do 3 ao 4, como em [slot-do-cdc-atrasado](#slot-do-cdc-atrasado):

1. Confira as pré-condições do passo 2: o slot está `active = t`, o lag está abaixo de 1 MiB (1 048 576 bytes) e nenhuma transação aberta é mais velha que o corte de 5 minutos. As três valem ao mesmo tempo, porque `created_at` é o início da transação: uma transação longa grava uma linha com `created_at` antigo só ao fazer commit, e marcá-la sem o conector ter lido a perderia.

```
$PSQL -tAc "SELECT active, pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) AS lag_bytes FROM pg_replication_slots WHERE slot_name = 'radar_outbox'"
$PSQL -tAc "SELECT count(*) FROM pg_stat_activity WHERE xact_start < now() - interval '5 minutes' AND pid <> pg_backend_pid()"
```

   O primeiro deve dar `t` e menos de 1 048 576; o segundo, 0. Se o lag estiver alto, espere ou siga [slot-do-cdc-atrasado](#slot-do-cdc-atrasado).
2. Com o conector saudável e ainda rodando, marque como publicado só o que ele já entregou, isto é, as linhas mais velhas que o corte de 5 minutos: `$PSQL -c "UPDATE outbox.outbox_event SET published_at = now() WHERE published_at IS NULL AND created_at < now() - interval '5 minutes'"`. Este passo só vale com o conector saudável.
3. Pare o conector, desligue o CDC e apague os offsets dele: `$COMPOSE stop debezium && $PSQL -v ON_ERROR_STOP=1 -f - < infra/cdc/disable-cdc.sql && $COMPOSE rm -f -v debezium && docker volume rm radar_cdcdata`. O script apaga o slot, a publicação, a tabela do batimento, a coluna e a função, e falha se o slot ainda estiver ativo. O volume `cdcdata` guarda o offset do conector: se ficar, uma nova ativação falha com `Last recorded offset is no longer available` (o nome do volume leva o nome do projeto do compose).
4. Reinicie o app com `RADAR_OUTBOX_MODE=polling` (o padrão). O relay publica o que ficou sem `published_at`, as linhas dos últimos 5 minutos: algumas já saíram pelo conector, então saem de novo (duplicata, absorvida pelo Inbox).

Validado no compose com a pilha inteira: ida e volta sem perda nem duplicata em massa (os 1 633 eventos do seed saíram pelo CDC e foram todos projetados; depois da volta o relay publicou só as linhas novas).

No modo `cdc` a limpeza diária apaga por `created_at` (7 dias), porque ninguém grava `published_at`. Um `aggregate_type` que não seja `Procurement` nem `Alert` não tem regra no `RegexRouter` e vai para um tópico com o nome do tipo (por exemplo `Foo`), em vez de falhar como o relay (`PublishException`): ao criar um tipo novo, acrescente a regra em `infra/cdc/connector.properties` e o tópico em `radar.outbox.topics.*`.

### Backfill

Carrega dias do PNCP pelo operador. A janela noturna é de 00:00 a 06:00 (America/Sao_Paulo); `force=true` ignora a janela. Até 400 dias por chamada, um dia por vez, uma transação por página.

```
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/ops/backfill?from=2026-10-01&to=2026-10-31&modality=6&force=true"
$PSQL -c "SELECT id, kind, status, window_start::date, cursor_page, fetched FROM ingest.ingestion_run ORDER BY id DESC LIMIT 5"
```

`make backfill FROM=... TO=...` faz a mesma chamada com o token de `scripts/ops-token.sh`; `make seed` carrega o dia gravado. Uma execução `FAILED` é retomada pela mesma chamada. Durante um backfill histórico, desligue o matcher (`radar.alerts.matcher.enabled=false`) para não gerar avisos de editais antigos.

### Replay da projeção

`POST /ops/replay` e `make replay` chegam no PR 4.3. Até lá a visão de busca só é reconstruída pelo reprocessamento dos eventos do tópico `radar.procurement.v1`.

### Reprocessar a DLQ

Um registro chega à DLQ quando o consumidor desiste dele: mensagem que nunca será lida (JSON ilegível, campo obrigatório ausente), falha que o banco rejeita (valor grande demais, violação de restrição) depois de 3 tentativas, ou envio de e-mail recusado depois de 3 tentativas. Queda do banco nunca vai para a DLQ: o consumidor espera e tenta de novo a cada 5 s. A política completa está no [ADR-0026](adr/0026-dlq-reprocessamento.md).

O tópico `radar.procurement.v1.dlq` recebe o que falhou na projeção de busca e no casamento de alertas; `radar.alert.v1.dlq`, o que falhou no envio de e-mail. O consumidor `ops-dlq-recorder` copia cada registro para `ops.dlq_message`, com a causa.

1. Veja o que está parado: o painel (`/ops/painel`, linha "Fila de mensagens mortas") ou

```
$PSQL -c "SELECT id, topic, consumer_group, error_class, left(error_message, 80) AS erro, recorded_at FROM ops.dlq_message WHERE reprocessed_at IS NULL ORDER BY id"
```

2. Corrija a causa: o reprocessamento reenvia a mesma chave e o mesmo valor, então uma mensagem malformada volta à DLQ. Para um erro de código ou de dado do lado do consumidor, publique a correção primeiro. `error_class` e `error_message` dizem o que falhou; a mensagem original está em `message_value`.
3. Reenvie, tudo de um tópico ou uma mensagem só:

```
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/ops/dlq/radar.procurement.v1.dlq/reprocess"
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/ops/dlq/radar.alert.v1.dlq/reprocess?id=42"
```

A resposta (`202`) traz `count`, a quantidade reenviada ao tópico original com o cabeçalho `x-reprocessed-from=dlq`, e `more`: cada chamada trata até 500 mensagens, e com `more=true` você repete o comando até vir `false`. Tópico ou `id` desconhecido responde `404` (`problem+json`). Se o broker recusar uma mensagem, o reenvio para na primeira falha e responde `503` (`reprocess-interrupted`, [problema](problems/reprocess-interrupted.md)) com o `count` feito até ali; a mensagem que falhou continua pendente, com o motivo em `reprocess_result`, e uma nova chamada recomeça dela. As mensagens que já falharam passam para o fim da fila, atrás das nunca tentadas, mas uma que falha sempre trava a página que a alcança: para pular a mensagem que falha, reprocesse as outras com `?id=`. Alertas e eventos velhos reprocessados não são descartados pelo limite de idade (`max-event-age`): o cabeçalho `x-reprocessed-from=dlq` o dispensa.
4. Confirme: `$PSQL -c "SELECT id, reprocessed_at, reprocess_result FROM ops.dlq_message ORDER BY id DESC LIMIT 5"` e o painel. Se a mensagem falhar de novo, ela volta à DLQ como um registro novo.

Repetir o comando é seguro: os consumidores guardam o `event_id` no inbox e absorvem o duplicado. O contador `dlq_messages_total{topic}` conta o que foi enviado à DLQ, não o que foi reenviado.

### Manutenção de partições

Roda sozinha na partida do app e todo dia às 03:15 UTC (`radar.ingest.partitions.cron`) e cobre o mês atual e os próximos `radar.ingest.partitions.months-ahead` (3). Para rodar à mão, use o comando do passo 1 de [particao-ausente](#particao-ausente).

### Troca de coluna: centavos do valor estimado

Sequência do ADR-0010 (expand, backfill, flag, contract), em ordem:

1. A migration V14 criou `estimated_value_cents`, o gatilho e o índice dos pendentes no deploy. Ela pede trava exclusiva em `ingest.procurement` e desiste em 5 s (`lock_timeout`) se houver transação longa aberta: o deploy falha sem aplicar nada, então encerre a transação (veja `pg_stat_activity`) e repita. Confira: `$PSQL -c "SELECT count(*) FROM ingest.procurement WHERE estimated_value_cents IS NULL AND estimated_value IS NOT NULL"` (o que falta preencher).
2. Ligue o job em uma instância só (ele não tem trava entre instâncias; duas repetem trabalho sem dano): `RADAR_INGEST_VALUE_CENTS_BACKFILL_ENABLED=true` e reinicie o app. Acompanhe a métrica `ingest_value_cents_pending_rows`; ao chegar a 0, o job pode ficar ligado (sem nada a fazer ele só conta). O backfill dobra o arquivo das partições com linhas antigas ([vacuum-bloat](benchmarks/db/vacuum-bloat.md)).
3. Com o pendente em 0 (recomendado; a leitura cai para o valor antigo nas linhas sem centavos, então não há perda se for antes), ligue `READ_VALUE_CENTS` em `/ops/togglz`. Para voltar, desligue a flag no mesmo lugar.
4. O contract (`db/contract/V17__drop_estimated_value.sql.next`) só entra na release seguinte, depois de uma release inteira com a flag ligada.

### Rotação de segredos

- Senha do banco (`radar`): `$PSQL -c "ALTER ROLE radar PASSWORD '<nova>'"`, grave a nova senha em `infra/compose/secrets/db_password` (ou no Secret do Kubernetes, chave `SPRING_DATASOURCE_PASSWORD`) e reinicie o app. A variável `POSTGRES_PASSWORD_FILE` do Postgres só vale na criação do volume.
- Papel `radar_read_writer` (lado de leitura): a migration V6 só aplica a senha na criação do papel. Para rotacionar: `$PSQL -c "ALTER ROLE radar_read_writer PASSWORD '<nova>'"`, atualize `infra/compose/secrets/read_writer_password` (ou `RADAR_READ_WRITER_PASSWORD`) e reinicie o app, que usa o mesmo valor no Flyway e na fonte de dados da busca. Sem o `ALTER ROLE` o app não conecta e a busca devolve 5xx ([busca-indisponivel](#busca-indisponivel)).
- Segredo do cliente `radar-web` (`RADAR_WEB_CLIENT_SECRET`) e do `radar-ops-cli`: troque no Keycloak (console em http://localhost:8180) e depois na variável de ambiente; reinicie o app.
- Nunca grave segredo em `application.properties`, imagem ou Helm values: o inventário completo está em `docs/config-12-factor.md`.

### Restaurar de backup

Ainda não há procedimento nem ensaio de restauração de backup. O rollback de imagem, que não restaura dados, está em [Rollback](#rollback).

<a id="rollback"></a>

## Rollback

Volta a aplicação ao deploy anterior, pelo digest da imagem. Vale quando o deploy falha (readiness ou smoke) e, depois de um deploy que passou, quando um erro aparece e o `.deployed.previous` ainda existe.

```
make deploy TAG=<tag>   # troca o app; se o compose ou o smoke falharem, volta sozinho
make rollback           # volta ao registro de infra/compose/.deployed.previous
cat infra/compose/.deployed infra/compose/.deployed.previous
```

Cada rollback consome o `.deployed.previous`: uma segunda chamada falha com `rollback: sem deploy anterior registrado`. Sem deploy anterior, `make deploy` também falha, com a mesma mensagem.

O que conferir:

- `infra/compose/.deployed`: tag e digest do que está no ar (o digest é o `RepoDigests` quando existe, senão o id da imagem). Depois de um rollback, a tag é a anterior.
- `docker compose -f infra/compose/docker-compose.yml ps app`: o `app` deve estar `healthy`.
- `curl -fsS http://localhost:8080/actuator/health/readiness` e `curl -fsS http://localhost:8080/actuator/info`.
- Logs: `make logs SERVICE=app`, ou só os erros: `docker compose -f infra/compose/docker-compose.yml logs --no-log-prefix --tail=1000 app | grep '"level":"ERROR"'`.

Limite do escopo de demonstração: o rollback usa a imagem anterior que está no daemon Docker e o estado em `infra/compose/.deployed*`. Num runner novo do CI nenhum dos dois existe, então o rollback só vale no mesmo runner. Um deploy de verdade guardaria o estado e as imagens fora do runner (ver ADR-0022).

Escalonamento: se o rollback falhar (`rollback: ... nao subiu limpo`), o app pode estar fora do ar. Escale para o time com a saída dos dois comandos acima e os logs do app; o `.deployed.previous` continua no lugar para nova tentativa.

### Ensaio de 2026-10-23

Imagens: `radar-licitacoes:good` (build local do código da main) e `radar-licitacoes:bad`, feita sobre `good` com `/app/config/application.properties` desligando `management.endpoint.health.probes.enabled`. Sem o readiness, o `--wait` do compose marca o app como não saudável e o deploy precisa voltar. Trecho real do log, com as linhas de progresso do compose omitidas (`...`). O `good` é o deploy anterior, feito antes no mesmo ensaio; as datas são o início e o fim de cada chamada, porque o compose não grava horário nas próprias linhas:

```
2026-10-23T01:17:01-03:00
deploy: good no ar (radar-licitacoes@sha256:326e22ef8417187089b4c4c23de7989ceb7d8c73a7e427d46ae1ae7f83a04a54)
2026-10-23T01:17:17-03:00
scripts/deploy.sh "bad"
...
container radar-app-1 is unhealthy
deploy: bad falhou
deploy: voltando ao deploy anterior
...
smoke: ok em http://localhost:8080
rollback: voltou para good (radar-licitacoes@sha256:326e22ef8417187089b4c4c23de7989ceb7d8c73a7e427d46ae1ae7f83a04a54)
make: *** [Makefile:175: deploy] Error 1
deploy bad exit 2
2026-10-23T01:19:37-03:00
```

Resultado conferido depois do ensaio: `.deployed` com `tag=good` e o digest `326e22ef…`, `.deployed.previous` removido, o `app` no ar com a imagem de `good` (tag `rollback`), `healthy`, e readiness 200. Início do `bad` às 01:17:17 e fim às 01:19:37 (horário local, UTC-3). Este trecho é da repetição feita depois dos ajustes do script (mensagens de falha separadas); o primeiro ensaio, de 01:04 a 01:07, tinha as mesmas ações, com a falha numa linha só (`deploy: bad falhou, voltando ao deploy anterior`).
