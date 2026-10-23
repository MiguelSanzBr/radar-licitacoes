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

3. Erros do projetor: `$COMPOSE logs --no-log-prefix --tail=1000 app | grep -E 'ProcurementEventsListener|search'`. Queda do banco (conexão, tempo esgotado, trava) é tentada a cada 5 s sem limite e nunca vai para a DLQ; registro que nunca valida, ou que o banco rejeita (valor grande demais, por exemplo), vai para `radar.procurement.v1.dlq` e aparece no painel (veja "Reprocessar a DLQ").
4. Carga concorrente: um backfill histórico gera muitos eventos de uma vez e o lag sobe sem defeito. Confira: `$PSQL -c "SELECT id, kind, status, window_start::date FROM ingest.ingestion_run WHERE status = 'RUNNING'"`.
5. Projetor desligado: `radar.search.projector.enabled=false`.

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

### Redis fora do ar (limite abre)

**Sintoma.** `rate_limit_fail_open_total` cresce e o log traz, no máximo uma vez por minuto, `WARN Rate limiter unavailable, public reads pass unmetered (fail-open)`. As respostas das rotas públicas ficam sem `RateLimit-*`.

**Impacto.** A leitura pública continua no ar, sem limite (ADR-0027). Com o Redis recusando conexão, o cliente rejeita os comandos na hora e a leitura não espera. Com o Redis pendurado (sem responder), cada leitura espera até 250 ms (`spring.data.redis.timeout`) antes de seguir e a latência sobe nesse valor enquanto durar.

**Ação.** Suba o Redis (`$COMPOSE up -d redis`); o cliente reconecta sozinho. O estado dos baldes se perde sem prejuízo: balde ausente é balde cheio. Se a queda for longa e houver abuso, reduza a exposição na borda (proxy) ou desligue o limite com `RADAR_RATE_LIMIT_ENABLED=false` para eliminar a espera de 250 ms.

**Cuidados.** O Redis precisa de `maxmemory-policy volatile-ttl` (toda chave tem TTL); com `noeviction` e a memória cheia o limite passa a abrir para todos (`rate_limit_fail_open_total` sobe com `OOM` no log). Em produção o log DEBUG de requisição e resposta deve ficar desligado: a resposta de `POST /api/v1/api-keys` leva a chave em claro.

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
4. O contract (`db/contract/V16__drop_estimated_value.sql.next`) só entra na release seguinte, depois de uma release inteira com a flag ligada.

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
