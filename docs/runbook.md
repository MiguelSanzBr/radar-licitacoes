# Runbook de operação

Cada alerta de `infra/prometheus/rules/slo.yml` aponta para uma seção deste arquivo pelo campo `runbook_url`. `make lint-observability` falha se uma âncora citada pelas regras não existir aqui. Os comandos foram rodados contra o compose (`make up`); em Kubernetes, troque `$COMPOSE exec` por `kubectl exec`.

## Convenções

```
COMPOSE="docker compose -f infra/compose/docker-compose.yml"
PSQL="$COMPOSE exec postgres psql -U radar -d radar"
```

- Logs da aplicação: `make logs SERVICE=app` (JSON com `traceId`, `correlationId`, `level`). Para filtrar sem ficar preso no `-f`: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep '"level":"ERROR"'`.
- Métricas: Grafana em http://localhost:3000 (`admin` / `admin`), pasta dos dashboards "Radar". No compose a aplicação envia métricas por OTLP; as regras de `slo.yml` só são avaliadas onde há Prometheus carregando o arquivo (Kubernetes), então no compose use os dashboards e as consultas abaixo como equivalente.
- Painel de saúde: `/ops/health-panel` chega no PR 4.3. Até lá use as consultas SQL e os dashboards desta página.
- Token de operador (para `/ops/*` e `/actuator/prometheus`):

```
TOKEN=$(scripts/ops-token.sh)
curl -fsS -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/prometheus | grep -E '^(outbox_|ingest_)'
```

- Escalonamento: o "time" é o dono do serviço. Alerta `critical` exige resposta imediata e abre incidente (linha do tempo e post-mortem sem culpados em `docs/post-mortem/`); `warning` entra na fila do dia. Quando o diagnóstico não fecha em 30 minutos, ou há perda possível de dado, escale para o time e registre o que já foi descartado.

| Alerta | Severidade | Seção |
|---|---|---|
| `OutboxOldestAgeHigh` | warning | [outbox-atrasado](#outbox-atrasado) |
| `ProjectionLagHigh` | warning | [projecao-atrasada](#projecao-atrasada) |
| `SearchLatencyFastBurn`, `SearchLatencySlowBurn` | critical, warning | [busca-lenta](#busca-lenta) |
| `SearchErrorBudgetFastBurn`, `SearchErrorBudgetSlowBurn` | critical, warning | [busca-indisponivel](#busca-indisponivel) |
| `AlertDeliveryLatencyHigh` | warning | [alerta-atrasado](#alerta-atrasado) |
| `RadarTargetDown` | critical | [alvo-fora](#alvo-fora) |
| `RadarSemTrafego` | warning | [sem-trafego](#sem-trafego) |
| `ParticaoFutura` | critical | [particao-ausente](#particao-ausente) |

<a id="outbox-atrasado"></a>

## Outbox atrasado (`OutboxOldestAgeHigh`)

**Sintoma.** `outbox_oldest_age_seconds` acima de 5 s por 10 minutos: eventos gravados pela coleta não chegam ao Kafka.

**Impacto.** A busca e os alertas ficam defasados. Não há perda: as linhas continuam em `outbox.outbox_event` e o relay as publica quando o obstáculo some (ADR-0018).

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

3. Erros do projetor: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep -E 'skipping record|search'`. Falha de banco é tentada a cada 5 s sem limite; registro que nunca valida é pulado com o log `skipping record` (a DLQ chega no PR 4.3).
4. Carga concorrente: um backfill histórico gera muitos eventos de uma vez e o lag sobe sem defeito. Confira: `$PSQL -c "SELECT id, kind, status, window_start::date FROM ingest.ingestion_run WHERE status = 'RUNNING'"`.
5. Projetor desligado: `radar.search.projector.enabled=false`.

**Ação.** Backfill em andamento: aguarde. Banco indisponível: restaure o Postgres, o projetor recupera sozinho. Registros pulados: guarde o offset do log e reprocesse pela DLQ quando existir (veja "Operações rotineiras").

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

**Ação.** Postgres fora: suba e confirme a prontidão. Senha desalinhada: alinhe o papel e o segredo e reinicie o app. Erro após deploy: volte à imagem anterior (PR 7.5).

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

## Operações rotineiras

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

Os tópicos `radar.procurement.v1.dlq` e `radar.alert.v1.dlq` existem, mas o consumidor que grava `dlq_message` e o `POST /ops/dlq/{topic}/reprocess` chegam no PR 4.3. Até lá, registros pulados só aparecem no log como `skipping record`.

### Manutenção de partições

Roda sozinha na partida do app e todo dia às 03:15 UTC (`radar.ingest.partitions.cron`) e cobre o mês atual e os próximos `radar.ingest.partitions.months-ahead` (3). Para rodar à mão, use o comando do passo 1 de [particao-ausente](#particao-ausente).

### Rotação de segredos

- Senha do banco (`radar`): `$PSQL -c "ALTER ROLE radar PASSWORD '<nova>'"`, grave a nova senha em `infra/compose/secrets/db_password` (ou no Secret do Kubernetes, chave `SPRING_DATASOURCE_PASSWORD`) e reinicie o app. A variável `POSTGRES_PASSWORD_FILE` do Postgres só vale na criação do volume.
- Papel `radar_read_writer` (lado de leitura): a migration V6 só aplica a senha na criação do papel. Para rotacionar: `$PSQL -c "ALTER ROLE radar_read_writer PASSWORD '<nova>'"`, atualize `infra/compose/secrets/read_writer_password` (ou `RADAR_READ_WRITER_PASSWORD`) e reinicie o app, que usa o mesmo valor no Flyway e na fonte de dados da busca. Sem o `ALTER ROLE` o app não conecta e a busca devolve 5xx ([busca-indisponivel](#busca-indisponivel)).
- Segredo do cliente `radar-web` (`RADAR_WEB_CLIENT_SECRET`) e do `radar-ops-cli`: troque no Keycloak (console em http://localhost:8180) e depois na variável de ambiente; reinicie o app.
- Nunca grave segredo em `application.properties`, imagem ou Helm values: o inventário completo está em `docs/config-12-factor.md`.

### Restaurar de backup

Procedimento e ensaio de restauração chegam no PR 7.5.
