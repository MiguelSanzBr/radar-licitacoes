# ADR-0022: Imagem multi-stage, compose como ambiente oficial e deploy por digest

- Status: proposta
- Data: 2026-10-19

## Contexto

O projeto não usa nuvem permanente. O ambiente oficial é o `docker compose up`, e o deploy é a troca da imagem por um digest conhecido, com volta ao digest anterior se o smoke falhar. Tudo isso parte de uma imagem pequena, reproduzível, sem root e que suba rápido. Esta ADR começa pela imagem (PR 7.1). As seções de compose (PR 7.2) e de deploy e rollback (PR 7.5) são preenchidas nesses PRs.

## Decisão

### Imagem

- `Dockerfile` na raiz, com os estágios `poms`, `build`, `extract`, `jre` e `runtime`. A imagem empacota o módulo `core`; quando existir o módulo `apps/monolith`, só o caminho do módulo muda.
- Imagens base fixadas por tag e digest: JDK `eclipse-temurin:25-jdk-alpine` (Temurin 25.0.4.1+1) e runtime `alpine:3.22`. Os digests ficam em linhas `FROM` literais do Dockerfile (o Dependabot só atualiza `FROM` literal) e são resolvidos com `docker buildx imagetools inspect`.
- Cache de dependências: o estágio `poms` guarda só o wrapper e os `pom.xml` de todos os módulos, então a camada de `dependency:go-offline` só é refeita quando uma dependência muda. O `~/.m2` fica num cache mount do BuildKit.
- Layered jar extraído com `-Djarmode=tools extract --layers`, copiado na ordem `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`. Sem `--launcher`: o jar extraído lista as bibliotecas no `Class-Path`, e é isso que deixa o AOT cache arquivar as classes das dependências (pelo launcher, elas ficam fora do cache).
- Runtime sob medida com `jlink`: os módulos vêm do `jdeps` sobre o jar e as bibliotecas, mais `jdk.crypto.ec`, `jdk.unsupported`, `jdk.localedata` (formatação pt-BR), `jdk.charsets`, `jdk.zipfs`, `jdk.management` e as ferramentas de profiling do marco M6 (`jdk.jcmd`, `jdk.attach`, `jdk.management.jfr`, `jdk.management.agent`, `jdk.naming.dns`, `jdk.net`, `jdk.security.auth`). Como o `jdeps` roda a cada build, uma dependência nova que exija outro módulo é detectada sozinha. Só módulos usados por reflexão ou por configuração precisam entrar à mão na lista.
- AOT cache do JDK 25 (`-XX:AOTCacheOutput`): o treino roda no estágio final, porque o cache só vale para o mesmo JVM, o mesmo classpath e o mesmo caminho. O treino sobe a aplicação até o refresh do contexto (`-Dspring.context.exit=onRefresh`) com o perfil de produção `aot-train` (`application-aot-train.properties`). O `noop` é só de teste e não serve ao treino. O PR que trouxer o datasource precisa acrescentar a esse perfil as exclusões que desligam datasource, Flyway e Kafka no startup, senão o treino falha o build.
- Usuário não root `radar` (uid 10001), `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`, `EXPOSE 8080` e `HEALTHCHECK` com `wget` (busybox) em `/actuator/health/liveness`.
- Actuator expõe só `health` e `info`, com probes de liveness e readiness. Prometheus entra depois, com a observabilidade.

### Medições (2026-10-19, host com carga)

Os tamanhos são o `Size` local e descompactado de `docker image inspect`, não o tamanho comprimido no registry (a variante JRE tinha 109 MB comprimidos).

| Variante | Tamanho local (descompactado) |
| --- | --- |
| JRE `eclipse-temurin:25-jre-alpine` + AOT cache | 418 MB |
| `jlink` sobre `alpine:3.22` + AOT cache (adotada) | 256 MB |

O `jlink` reduziu 39%, acima do limiar de 30% combinado, e o AOT cache continuou valendo. O cache em si pesa cerca de 57 MB.

Inicialização (`Started RadarApplication in X s`, 3 execuções após uma de aquecimento, imagem `jlink` final, perfil `noop`):

| Rodada | Com AOT cache | Sem AOT cache |
| --- | --- | --- |
| 1 | 1,774 / 1,513 / 1,825 (mediana 1,774 s) | 4,210 / 4,202 / 4,391 (mediana 4,210 s) |
| 2 | 1,690 / 2,103 / 0,923 (mediana 1,690 s) | 4,186 / 4,090 / 3,964 (mediana 4,090 s) |

O host tinha outras cargas, por isso as rodadas variam. Nas duas, o cache reduziu a inicialização a menos da metade. Os números são do app mínimo de hoje e serão refeitos quando houver banco e Kafka.

## Alternativas rejeitadas (e por quê)

- JRE `25-jre-alpine` sem `jlink`: 162 MB maiores (418 contra 256 MB), sem ganho funcional.
- CDS clássico (`-XX:ArchiveClassesAtExit`): o AOT cache do JDK 25 também carrega o link das classes e o perfil de execução, e não exigiu mais passos. Fica como plano B se o AOT cache falhar com alguma dependência.
- `--launcher` no extract: as classes das bibliotecas saem do cache, que perde o ganho principal.
- Imagem `-jre` Ubuntu (glibc): esperada maior e com mais pacotes para atualizar (expectativa, não medida).
- Mapear o socket do Docker no Trivy: o scan roda sobre um tar da imagem (`docker save`), sem dar ao container acesso ao daemon.

## Consequências

- O cache depende do JVM exato: trocar o digest da base sem rebuild completo invalida o cache, e a JVM ignora um cache inválido sem falhar, apenas sobe mais devagar. O teste de container confere que `/app/app.aot` existe e que a inicialização não registra aviso de AOT.
- O Trivy varre o SO (Alpine) e os jars da imagem final, e também a imagem de build do JDK fixada no Dockerfile. Ele não confere o JDK dentro do runtime `jlink` (binário em `/opt/jre`, fora de gerenciador de pacotes): vulnerabilidades do JDK (Temurin 25.0.4.1+1) não são verificadas por máquina. A mitigação é acompanhar os avisos de segurança do Temurin e subir o digest do `eclipse-temurin` em um PR de chore a cada atualização crítica.
- Scan atual (Trivy 0.76.0, `--severity CRITICAL,HIGH --ignore-unfixed`): 0 vulnerabilidades no SO e nos jars da imagem final e na imagem de build.

## Prova

- `it-image/src/test/java/br/dev/miguelsanzbr/radar/image/RadarImageIT.java`, executado por `make image image-test`.
- `make image-size` imprime o tamanho; `make image-scan` roda o Trivy.
