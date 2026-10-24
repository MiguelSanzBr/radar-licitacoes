# ADR-0022: Imagem multi-stage, compose como ambiente oficial e deploy por digest

- Status: proposta
- Data: 2026-10-19

## Contexto

O projeto não usa nuvem permanente. O ambiente oficial é o `docker compose up`, e o deploy é a troca da imagem por um digest conhecido, com volta ao digest anterior se o smoke falhar. Tudo isso parte de uma imagem pequena, reproduzível, sem root e que suba rápido. Esta ADR começou pela imagem (PR 7.1) e traz agora o compose (PR 7.2). A seção de deploy e rollback (PR 7.5) é preenchida nesse PR.

## Decisão

### Imagem

- `Dockerfile` na raiz, com os estágios `poms`, `build`, `extract`, `jre` e `runtime`. A imagem empacota o módulo `core`; quando existir o módulo `apps/monolith`, só o caminho do módulo muda.
- Imagens base fixadas por tag e digest: JDK `eclipse-temurin:25-jdk-alpine` (Temurin 25.0.4.1+1) e runtime `alpine:3.22`. Os digests ficam em linhas `FROM` literais do Dockerfile (o Dependabot só atualiza `FROM` literal) e são resolvidos com `docker buildx imagetools inspect`.
- Cache de dependências: o estágio `poms` guarda só o wrapper e os `pom.xml` de todos os módulos, então a camada de `dependency:go-offline` só é refeita quando uma dependência muda. O `~/.m2` fica num cache mount do BuildKit.
- Layered jar extraído com `-Djarmode=tools extract --layers`, copiado na ordem `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`. Sem `--launcher`: o jar extraído lista as bibliotecas no `Class-Path`, e é isso que deixa o AOT cache arquivar as classes das dependências (pelo launcher, elas ficam fora do cache).
- Runtime sob medida com `jlink`: os módulos vêm do `jdeps` sobre o jar e as bibliotecas, mais `jdk.crypto.ec`, `jdk.unsupported`, `jdk.localedata` (formatação pt-BR), `jdk.charsets`, `jdk.zipfs`, `jdk.management` e as ferramentas de profiling do marco M6 (`jdk.jcmd`, `jdk.attach`, `jdk.management.jfr`, `jdk.management.agent`, `jdk.naming.dns`, `jdk.net`, `jdk.security.auth`). Como o `jdeps` roda a cada build, uma dependência nova que exija outro módulo é detectada sozinha. Só módulos usados por reflexão ou por configuração precisam entrar à mão na lista.
- AOT cache do JDK 25 (`-XX:AOTCacheOutput`): o treino roda no estágio final, porque o cache só vale para o mesmo JVM, o mesmo classpath e o mesmo caminho. O treino sobe a aplicação até o refresh do contexto (`-Dspring.context.exit=onRefresh`) com o perfil de produção `aot-train` (`application-aot-train.properties`). O `noop` é só de teste e não serve ao treino. Com o datasource (JPA e Flyway) o perfil `aot-train` sobe o contexto sem abrir nenhuma conexão: `spring.flyway.enabled=false`, URL fictícia `jdbc:postgresql://127.0.0.1:1/aot`, `spring.datasource.hikari.initialization-fail-timeout=-1`, dialeto `PostgreSQLDialect` explícito, `spring.jpa.hibernate.ddl-auto=none` e `spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false`. O PR do Kafka acrescenta ali o que desligar do consumidor e do produtor.
- Heap inicial fixo (`-Xms64m`): o AOT cache carrega os objetos arquivados no heap inicial, que por padrão é 1/64 da memória do container (10 MB sob o `mem_limit: 640m` do app) e não os comporta; sem o piso a JVM aborta com "archived heap objects cannot be loaded". O coletor não é a causa: o treino roda sem limite (G1) e o runtime sob 640m cai no Serial, e esse cache carrega normalmente com o heap inicial maior. Por isso o cache não precisou ser refeito para comparar coletores no PR 6.2, e o coletor escolhido (G1, ADR-0020) é fixado em `JAVA_TOOL_OPTIONS` em vez de seguir a ergonomia. O teste de container sobe a imagem sob 640m e confere que o cache foi usado.
- Usuário não root `radar` (uid 10001), `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Xms64m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"`, `EXPOSE 8080` e `HEALTHCHECK` com `wget` (busybox) em `/actuator/health/liveness`.
- Actuator expõe só `health` e `info`, com probes de liveness e readiness. Prometheus entra depois, com a observabilidade.

### Medições (2026-10-19, host com carga)

Os tamanhos são o `Size` local e descompactado de `docker image inspect`, não o tamanho comprimido no registry (a variante JRE tinha 109 MB comprimidos).

| Variante | Tamanho local (descompactado) |
| --- | --- |
| JRE `eclipse-temurin:25-jre-alpine` + AOT cache, app mínimo (web e actuator) | 418 MB |
| `jlink` sobre `alpine:3.22` + AOT cache (adotada), app mínimo | 256 MB |
| `jlink` sobre `alpine:3.22` + AOT cache, app com JPA, Flyway e Postgres | 393 MB |

O `jlink` reduziu 39%, acima do limiar de 30% combinado, e o AOT cache continuou valendo. Com o app mínimo o cache pesava cerca de 57 MB; com JPA e Flyway são 111 MB (as bibliotecas somam 53 MB e o runtime `jlink` 75 MB), que explicam o salto de 256 para 393 MB. A comparação de 39% é da época do app mínimo.

Inicialização (`Started RadarApplication in X s`, 3 execuções após uma de aquecimento, imagem `jlink` final com JPA e Flyway, Postgres 18 em container na mesma rede):

| Rodada | Com AOT cache | Sem AOT cache |
| --- | --- | --- |
| 1 | 3,093 / 3,020 / 2,121 (mediana 3,020 s) | 6,018 / 4,872 / 6,059 (mediana 6,018 s) |
| 2 | 2,127 / 1,737 / 1,658 (mediana 1,737 s) | 5,225 / 7,628 / 5,900 (mediana 5,900 s) |

O host tinha outras cargas, por isso as rodadas variam. Nas duas, o cache reduziu a inicialização à metade ou menos. Os números serão refeitos quando houver Kafka.

### Compose (`infra/compose/docker-compose.yml`)

`make up` é o ambiente oficial: cria `.env` (cópia de `.env.example`) e `secrets/db_password` (valor aleatório) se faltarem, constrói a imagem se ela não existir e roda `docker compose up -d --wait`. Do zero, com as imagens já baixadas, tudo fica saudável em cerca de 2 minutos (o Keycloak em `start-dev` compila o servidor a cada criação do container). Todas as portas ficam presas a `127.0.0.1`. Toda imagem do compose é fixada por tag e digest (`tag@sha256:...`, resolvidos com `docker buildx imagetools inspect`); o Dependabot (`package-ecosystem: docker-compose`, diretório `/infra/compose`) propõe as atualizações.

| Serviço | Imagem | Limite | Observação |
| --- | --- | --- | --- |
| `postgres` | `postgres:18` | 256 MB | `shared_preload_libraries=pg_stat_statements`; `infra/postgres/init/01-extensions.sql` cria `pg_stat_statements` e `pgstattuple`; volume `pgdata` em `/var/lib/postgresql` (layout do 18); `pg_isready` |
| `kafka` | `apache/kafka:4.1.0` | 512 MB | KRaft com um nó, heap de 256 MB, listener interno `kafka:9092` e externo `localhost:9094`; saúde por `nc -z` (o `kafka-broker-api-versions.sh` sobe uma JVM a cada 5 s) |
| `redis` | `redis:8.8-alpine` | 96 MB | sem persistência; a aplicação ainda não o usa (marco M5), mas ele faz parte da pilha de um comando |
| `keycloak` | `quay.io/keycloak/keycloak:26.8` | 896 MB | `start-dev --import-realm` com `infra/keycloak/realm-radar.json` somente leitura; saúde pela porta de gerenciamento 9000 via `/dev/tcp` (a imagem não tem `curl`) |
| `mailpit` | `axllent/mailpit:v1.31.6` | 64 MB | SMTP 1025, interface 8025 (`SPRING_MAIL_HOST=mailpit`; o envio real chega com os alertas) |
| `otel-lgtm` | `grafana/otel-lgtm:0.36.0` | 1792 MB | saúde pelo `/api/health` do Grafana (a `app` espera por ele); Grafana 3000; recebe OTLP em 4318; dashboards de `infra/grafana/dashboards` provisionados por um provider extra (`radar.yml`) sobre o datasource Prometheus da imagem (uid `prometheus`) |
| `pncp-fixture` | `wiremock/wiremock:3.13.2-alpine` | 256 MB | faz o papel do PNCP (ver abaixo) |
| `app` | `radar-licitacoes:${RADAR_TAG:-local}` | 640 MB | `depends_on` com `service_healthy` em `postgres`, `kafka`, `keycloak`, `pncp-fixture` e `otel-lgtm` (este sobe em paralelo ao Keycloak, que é o mais lento, então não atrasa a partida); saúde em `/actuator/health/readiness`; porta 8080 |
| `apicurio` | `apicurio/apicurio-registry:3.3.3` | 512 MB | só com `--profile layer3` (`PROFILE=layer3 make up`); a aplicação só o usa com `RADAR_MESSAGING_FORMAT=avro` (ADR-0024) |

**Segredos.** `.env` e `secrets/` ficam fora do git; `scripts/compose-init.sh` gera `secrets/db_password` e `secrets/read_writer_password` com valores aleatórios, sem sobrescrever. A aplicação lê os segredos por `SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`, em que o nome do arquivo é a propriedade: `db_password` é montado como `spring.datasource.password` (o Postgres o lê por `POSTGRES_PASSWORD_FILE`) e `read_writer_password` é montado duas vezes, como `spring.flyway.placeholders.readWriterPassword` (a migration V6 cria o papel `radar_read_writer` com ele) e `radar.search.datasource.password` (a busca entra com ele). Os arquivos são `0644` porque o container roda como uid 10001; o diretório `secrets/` é `0700` no host. Limitação: o Postgres só aplica a senha do banco, e a V6 só cria o papel (sem `ALTER ROLE`), na primeira criação do volume. Apagar `secrets/` sem `CLEAN=1 make down` regenera os arquivos e deixa o banco com a senha antiga; trocar a senha de um volume existente exige `ALTER ROLE` manual. Os demais valores de `.env` são padrões de desenvolvimento do realm, já públicos em `.env.example`.

**Keycloak e o emissor.** O navegador e a aplicação precisam concordar com o `iss` dos tokens, mas só o navegador enxerga `localhost:8180`. Solução: `KC_HOSTNAME=http://localhost:8180` (o `iss` e as URLs de login saem sempre assim) e, na aplicação, `radar.oidc.issuer=http://localhost:8180/realms/radar` mais URIs explícitas de back-channel para as chaves (`jwk-set-uri` do resource server e do cliente) e para a troca do código por token (`token-uri`), todas em `http://keycloak:8080`. Como a ADR-0019 já configura os endpoints em vez de usar descoberta, não foi preciso `hostname-backchannel-dynamic` nem código novo. Conferido: o login do `fornecedor` pelo fluxo de código (redirecionamento para `localhost:8180`, volta em `/login/oauth2/code/keycloak`) termina na página `/assinatura`, e o token de client credentials tem `iss=http://localhost:8180/realms/radar`, `aud=radar-api` e o papel `operator`.

**Telemetria por push.** `/actuator/prometheus` exige o papel `operator`, então no compose não há scrape: a aplicação envia métricas (`MANAGEMENT_OTLP_METRICS_EXPORT_*`, passo de 15 s, unidade de tempo em segundos) e traces para `http://otel-lgtm:4318`. Os nomes que chegam ao Prometheus do `otel-lgtm` estão no ADR-0025. Os logs saem por stdout (`make logs`): o envio por OTLP exige o appender do OpenTelemetry no Logback, que o projeto não tem.

**PNCP do compose.** O serviço `pncp-fixture` serve o dia **2026-10-21, modalidade 6, 1.633 editais em 33 páginas** (dados públicos do PNCP, coletados em 2026-10-22), gravado em `infra/compose/seed/pncp-2026-10-21-mod6.jsonl.gz` (474 KB, uma página da resposta por linha) por `scripts/collect-pncp-fixture.sh`, que respeita a ADR-0017 (no máximo 1 requisição por segundo, `User-Agent` do projeto, espera de 15 s e nova tentativa no 429). Na partida o container descompacta as linhas em `__files/page-N.json`, e um único mapeamento do WireMock responde `publicacao` desse dia com o arquivo da página pedida e qualquer outra janela com 204, como o PNCP. A aplicação usa `RADAR_PNCP_BASE_URL=http://pncp-fixture:8080` e `RADAR_PNCP_REQUESTS_PER_SECOND=20` (o padrão de 0,2 req/s da ADR-0017 protege o PNCP real, não a fixture). A coleta incremental continua ligada e recebe 204. `make seed` pede o token de client credentials do cliente `radar-ops-cli` (papel `operator`, só no realm de desenvolvimento) e chama `POST /ops/backfill?from=2026-10-21&to=2026-10-21&modality=6&force=true`; o dia carrega em cerca de 30 s.

**E2E.** O módulo `e2e` (`./mvnw -Pe2e verify -pl e2e -am`, com a imagem construída) sobe o compose pelo `ComposeContainer`, confere que `/ops` sem token dá 401, faz o seed com token de operador e espera as 1.633 linhas em `ingest.procurement`, a execução em `DONE`, o outbox inteiro publicado e as métricas `ingest_items_total` e `pncp_items_fetched_total` no Prometheus do `otel-lgtm` (lido pelo proxy de datasources do Grafana). Quando a busca e os alertas existirem, a conferência por SQL vira uma busca pela API e entra o alerta recebido no Mailpit.

**Memória.** Medida com `docker stats --no-stream` depois do seed (docs/benchmarks/compose-memory.md): cerca de 2,8 GiB para a pilha inteira (2,0 GiB antes do seed), sendo o `otel-lgtm` (1,3 GiB) e o Keycloak (0,5 GiB) os maiores. Nenhum serviço precisou virar opcional neste host; `apicurio` fica no perfil `layer3`.

**Imagem da aplicação e tag.** O compose usa `radar-licitacoes:${RADAR_TAG:-local}`. A tag tem uma só fonte: `RADAR_TAG` no ambiente (o `make` exporta com padrão `local`, e o compose e o `e2e` leem do ambiente; `make image` constrói a mesma tag). A tag é global no daemon: dois checkouts construindo ao mesmo tempo sobrescrevem a mesma tag, e `RADAR_TAG` permite separar.

### Deploy e rollback

- `scripts/deploy.sh TAG` troca o serviço `app` do compose pela imagem da tag e confere o smoke (`scripts/smoke.sh`): o readiness e `GET /actuator/info`, que é público (as rotas `/api/v1` do contrato ainda respondem 404 no main), com prazo de 5 s por chamada. O `--wait` do compose já espera o healthcheck de readiness; o smoke acrescenta uma leitura de rota pública.
- O digest da imagem vai para `infra/compose/.deployed` (tag e digest do que está no ar). O deploy anterior é copiado para `.deployed.previous` antes de qualquer troca.
- Digest, e não tag, é a referência do rollback: a tag `good` pode ser movida depois do deploy, e o digest identifica o conteúdo. O digest é o `RepoDigests` quando existe, senão o id da imagem.
- Falhou o compose ou o smoke, `scripts/rollback.sh` põe no ar a imagem de `.deployed.previous`, marcada como `radar-licitacoes:rollback`, e confere o smoke de novo. Sem deploy anterior, o rollback falha com mensagem. Um rollback consome o registro anterior: não há volta em cascata.
- Compose num runner, escopo de demo: o compose já é o ambiente oficial e roda o mesmo código do desenvolvedor, então o deploy do CI é o mesmo comando (`make deploy`). Mas o estado (`.deployed`) e a imagem anterior vivem no runner, que é descartável. Um deploy de verdade precisa desse estado num lugar que sobreviva ao runner, e isso fica fora deste PR.
- Kubernetes: o mesmo contrato vira `helm upgrade --atomic`, que volta sozinho se o rollout falhar, e `helm rollback` com a revisão anterior, guardada no próprio cluster. O digest continua sendo a referência da imagem nos values.

## Alternativas rejeitadas (e por quê)

- JRE `25-jre-alpine` sem `jlink`: 162 MB maiores com o app mínimo (418 contra 256 MB), sem ganho funcional.
- CDS clássico (`-XX:ArchiveClassesAtExit`): o AOT cache do JDK 25 também carrega o link das classes e o perfil de execução, e não exigiu mais passos. Fica como plano B se o AOT cache falhar com alguma dependência.
- `--launcher` no extract: as classes das bibliotecas saem do cache, que perde o ganho principal.
- Imagem `-jre` Ubuntu (glibc): esperada maior e com mais pacotes para atualizar (expectativa, não medida).
- Mapear o socket do Docker no Trivy: o scan roda sobre um tar da imagem (`docker save`), sem dar ao container acesso ao daemon.
- Scrape do app pelo Prometheus no compose: o endpoint exige o papel `operator`, e dar um token de longa duração ao Prometheus do `otel-lgtm` (que não aceita arquivo de configuração extra sem trocar o da imagem) pesaria mais que o push por OTLP, que já é o caminho dos traces.
- Habilitar concessão por senha no `radar-web` para o `make seed`: a ADR-0019 proíbe que cliente do realm troque senha por token; o cliente `radar-ops-cli` com client credentials dá ao script o papel `operator` sem senha de usuário.
- WireMock com um mapeamento por página e as páginas descompactadas no git: 3,5 MB a mais no repositório; o `.jsonl.gz` (474 KB) com uma linha por página e um mapeamento com template dá o mesmo resultado.
- Healthcheck do Kafka com `kafka-broker-api-versions.sh`: confiável, mas uma JVM nova a cada intervalo; `nc -z` na porta do broker basta em nó único.

## Consequências

- Compose: as portas são fixas (8080, 8180, 3000, 8025, 5432, 9094): o redirect do cliente `radar-web` e o `KC_HOSTNAME` dependem de 8080 e 8180, então o `e2e` e o ambiente de desenvolvimento não rodam ao mesmo tempo (`make down` antes de `make e2e`).
- Os alertas de `infra/prometheus/rules/slo.yml` não são avaliados no compose (o Prometheus do `otel-lgtm` não carrega o arquivo); `up{job="radar"}` não existe sob push. Detalhes no ADR-0025.
- O cache depende do JVM exato: trocar o digest da base sem rebuild completo invalida o cache, e a JVM ignora um cache inválido sem falhar, apenas sobe mais devagar. O teste de container (que sobe um Postgres numa rede compartilhada e passa o datasource por variáveis de ambiente) confere que `/app/app.aot` existe e que a inicialização não registra aviso de AOT.
- O Trivy varre o SO (Alpine) e os jars da imagem final, e também a imagem de build do JDK fixada no Dockerfile. Ele não confere o JDK dentro do runtime `jlink` (binário em `/opt/jre`, fora de gerenciador de pacotes): vulnerabilidades do JDK (Temurin 25.0.4.1+1) não são verificadas por máquina. A mitigação é acompanhar os avisos de segurança do Temurin e subir o digest do `eclipse-temurin` em um PR de chore a cada atualização crítica.
- Scan atual (Trivy 0.76.0, `--severity CRITICAL,HIGH --ignore-unfixed`): 0 vulnerabilidades no SO e nos jars da imagem final e na imagem de build.

## Prova

- `it-image/src/test/java/br/dev/miguelsanzbr/radar/image/RadarImageIT.java`, executado por `make image image-test`.
- `make image-size` imprime o tamanho; `make image-scan` roda o Trivy.
- `e2e/src/test/java/br/dev/miguelsanzbr/radar/e2e/ComposeStackE2EIT.java`, executado por `make e2e` (precisa de `make image` antes).
- `make up`, `make seed`, `make logs` e `make down` (`CLEAN=1` apaga os volumes); `scripts/compose-init.sh`, `scripts/seed.sh` e `scripts/collect-pncp-fixture.sh`.
- `make test-scripts`: shellcheck sobre `scripts/deploy.sh`, `rollback.sh`, `smoke.sh` e `deploy-lib.sh`, e os testes bats de `scripts/test/deploy.bats` (docker e curl simulados). O ensaio de rollback forçado está no runbook, seção Rollback.
