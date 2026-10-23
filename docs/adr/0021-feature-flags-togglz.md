# ADR-0021: feature flags com Togglz, e o que é flag e o que é configuração

- Status: aceita
- Data: 2026-10-23

## Contexto

Quatro comportamentos de execução ligavam e desligavam por propriedade (`@ConditionalOnProperty`): a coleta incremental, o fechamento de propostas vencidas, a coleta histórica agendada e o resumo diário de alertas. Trocar a propriedade exige reiniciar a aplicação, e o operador não tem como parar uma coleta que está causando dano sem acesso ao ambiente de deploy. Ao mesmo tempo, o fator III do 12-factor manda que o que varia por ambiente venha do ambiente ([config-12-factor.md](../config-12-factor.md)). As duas regras precisam conviver sem duplicar o inventário.

## Decisão

Togglz 4.6.4, com o estado em JDBC (`ops.togglz_feature`, criada pelo Flyway V13) e o console em `/ops/togglz`, só para o papel `operator`.

**O que é flag.** Um comportamento que o operador precisa ligar ou desligar com a aplicação no ar, sem redeploy, e que o código consulta a cada execução. O estado pertence ao sistema em execução, não ao ambiente: fica no banco, é o mesmo para todas as instâncias e sobrevive a reinício.

**O que é configuração.** Um valor que muda por ambiente e é lido na partida: URLs, portas, credenciais, limites de recurso, nomes de tópicos, política de retentativas, tempos de espera, número máximo de buscas salvas (`radar.alerts.max-saved-searches`). Continua em propriedade ou variável de ambiente, e o inventário continua em [config-12-factor.md](../config-12-factor.md), que este ADR não repete.

**A fronteira em um caso.** Os interruptores de topologia (`radar.search.projector.enabled`, `radar.alerts.matcher.enabled`, `radar.alerts.notifier.enabled`, `radar.outbox.relay-enabled`, `radar.ops.dlq-recorder.enabled`, `radar.ingest.partitions.enabled`) ficam como propriedade. Eles decidem quais consumidores e agendadores existem em cada processo (hoje um só, no M8 divididos entre `ingestor` e `search-api`), e isso é papel do deploy. Uma flag é consultada dentro de um agendador que existe sempre.

| Flag | O que controla | Estado inicial (propriedade) | Por que é flag |
|---|---|---|---|
| `INCREMENTAL_SYNC` | agendador da coleta incremental | `radar.ingest.incremental.enabled` (padrão: desligada) | parar a coleta quando o PNCP responde mal ou durante um backfill |
| `CLOSE_EXPIRED` | fechamento de propostas vencidas | `radar.ingest.close-expired.enabled` (padrão: desligada) | parar a emissão de `ProcurementClosed` se uma regra errada fechar editais abertos |
| `BACKFILL_SCHEDULED` | coleta histórica agendada da madrugada | `radar.ingest.backfill.scheduled-enabled` (padrão: desligada) | ligar e desligar a carga histórica sem reiniciar |
| `ALERT_DIGEST` | resumo diário de alertas | `radar.alerts.digest.enabled` (padrão: ligada) | cortar o envio em massa de e-mail em um incidente de SMTP |
| `MAX_SAVED_SEARCHES_LIMIT` | aplica o limite de buscas salvas por assinante | ligada | desligar o limite em um caso de suporte; o valor do limite continua configuração |

O `application.properties` traz o valor inicial como `togglz.features.<FLAG>.enabled=${propriedade.antiga:padrão}`: quem configura por ambiente continua configurando do mesmo jeito. Enquanto a flag não tem linha em `ops.togglz_feature`, vale esse valor; depois que o operador mexe no console, vale o estado gravado. Mudar a variável de ambiente depois disso não muda a flag. Para voltar ao valor inicial, apague a linha: `DELETE FROM ops.togglz_feature WHERE feature_name = 'INCREMENTAL_SYNC'`.

**Como o código consulta.** O módulo `ops` expõe a porta `Flags` e o enum `Flag` (sem tipos do Togglz). Os agendadores de `ingest` e o resumo de `alerts` (adaptadores de entrada) leem a flag no início de cada execução. A aplicação de `alerts` mantém a própria porta `FeatureFlags`, implementada por um adaptador que delega a `Flags`, então domínio e aplicação não importam o Togglz. Os nomes das flags no enum e em `togglz.features` têm de ser os mesmos: o `FlagsIT` falha se divergirem.

**Segurança do console.** O console é um servlet do Togglz e uma página de navegador, então vive na cadeia de páginas (sessão OIDC), não na da API (JWT): `/ops/togglz/**` e `/ops/painel` exigem `ROLE_operator`, e a cadeia da API deixa esses dois caminhos de fora. O `UserProvider` vem do Spring Security (`togglz.console.feature-admin-authority=ROLE_operator`). Cada alteração exige dois tokens: o do CSRF do Spring (a cadeia de páginas o cobra em todo POST, e o módulo `togglz-spring-security` o põe nos formulários do console como `_csrf`) e o do próprio console (`togglz.console.validate-c-s-r-f-token`, ligado). Um POST sem o token do Spring recebe 403, e um sem o do console recebe 401; nenhum grava nada.

## Alternativas rejeitadas (e por quê)

- Continuar com propriedade e reinício: o operador não age sem redeploy.
- Serviço externo de flags (Unleash, LaunchDarkly): operar outro serviço para cinco interruptores; o estado em Postgres já é a fonte da verdade do projeto.
- `@RefreshScope` do Spring Cloud: traz o Spring Cloud inteiro (sem release train para o Boot 4.1 hoje) para um recurso que o Togglz resolve.
- Cache do Togglz ligado desde já: cada consulta é uma leitura por chave primária e acontece uma vez por execução de agendador ou por criação de busca salva; sem cache, a mudança vale na próxima leitura em todas as instâncias. Se a consulta virar ponto quente, `togglz.cache.enabled` com um TTL curto resolve.

## Consequências

- O agendador da coleta e o do resumo existem em todo processo e saem cedo quando a flag está desligada; os testes continuam com as propriedades antigas em `false`, que viram o estado inicial.
- Há uma consulta ao banco por execução de agendador e uma por criação de busca salva. Sem o banco, a flag levanta exceção; o agendador do Spring registra o erro e tenta no próximo ciclo, e a criação de busca falha como falharia de qualquer jeito.
- Limite cosmético conhecido: o console do Togglz traz estilos e um script embutidos na página, e a política de conteúdo do projeto (`script-src 'self'`, `style-src 'self'`) os bloqueia. A listagem e a edição das flags funcionam (o teste de rede prova), só parte do visual e dos atalhos de script se perde. Não relaxamos a política (nada de `unsafe-inline`) por causa de uma tela de operação.
- Dois estados da mesma flag (propriedade e banco) exigem a regra acima, que o runbook e o console deixam à vista.

## Prova

- `core/src/test/java/br/dev/miguelsanzbr/radar/ops/adapter/config/FlagsIT.java`: as flags do código são as features declaradas, o estado inicial vem das propriedades antigas, e virar uma flag grava em `ops.togglz_feature`.
- `core/src/test/java/br/dev/miguelsanzbr/radar/ops/adapter/out/togglz/TogglzFlagsTest.java`: a virada vale na hora.
- `ScheduledIncrementalSyncTest`, `ScheduledCloseExpiredTest`, `ScheduledBackfillTest` e `DigestJobTest`: flag desligada, nada roda.
- `core/src/test/java/br/dev/miguelsanzbr/radar/ops/adapter/config/TogglzConsoleIT.java`: pela rede, visitante vai ao login, `supplier` recebe 403, `operator` vê as features e muda uma com os dois tokens, e um POST sem o token do Spring (403) ou sem o do console (401) não grava.
