# syntax=docker/dockerfile:1

# Imagens base fixadas por tag e digest (resolvidos com `docker buildx imagetools inspect`), em
# linhas FROM literais para o Dependabot conseguir atualizar. O JDK e o runtime final sao ambos
# musl (Alpine), requisito para o jlink e para o AOT cache.

# Apenas wrapper e poms de todos os modulos: a camada so muda quando uma dependencia muda.
FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS poms
WORKDIR /src
COPY . .
RUN find . -type f ! -name pom.xml ! -name mvnw ! -path './.mvn/*' -delete \
 && find . -type d -empty -delete

FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS build
WORKDIR /src
COPY --from=poms /src ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package -pl core -am \
 && set -- core/target/core-*.jar && [ "$#" -eq 1 ] && mv "$1" /app.jar

# Sem --launcher: o jar extraido lista as bibliotecas no Class-Path, o que deixa o AOT cache
# arquivar tambem as classes das dependencias (com o launcher elas ficam fora do cache).
FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS extract
WORKDIR /x
COPY --from=build /app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination /x/out

# JRE sob medida: modulos calculados pelo jdeps sobre o jar e as bibliotecas, mais os que so
# aparecem por reflexao ou por configuracao (TLS, locale pt-BR, charsets, JMX, ferramentas de profiling).
FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS jre
COPY --from=extract /x/out/dependencies/ /app/
COPY --from=extract /x/out/application/ /app/
RUN mods="$(jdeps --ignore-missing-deps --multi-release 25 --print-module-deps \
      --class-path '/app/lib/*' /app/app.jar)" \
 && extra="jdk.crypto.ec,jdk.unsupported,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.management" \
 && extra="${extra},jdk.jcmd,jdk.attach,jdk.management.jfr,jdk.management.agent" \
 && extra="${extra},jdk.naming.dns,jdk.net,jdk.security.auth" \
 && jlink --add-modules "${mods},${extra}" \
      --strip-debug --no-man-pages --no-header-files --compress zip-9 --output /jre

FROM alpine:3.22@sha256:d406446bc05b82480756ce8f1287964d28768c23955fa9480d2a74a2eae7a654 AS runtime
RUN addgroup -g 10001 radar && adduser -D -u 10001 -G radar -H -s /sbin/nologin radar
ENV JAVA_HOME=/opt/jre PATH=/opt/jre/bin:$PATH
COPY --from=jre /jre /opt/jre
WORKDIR /app
COPY --from=extract /x/out/dependencies/ ./
COPY --from=extract /x/out/spring-boot-loader/ ./
COPY --from=extract /x/out/snapshot-dependencies/ ./
COPY --from=extract /x/out/application/ ./

# Treino do AOT cache: a aplicacao sobe ate o refresh do contexto e sai. O cache so vale para
# o mesmo JVM, o mesmo classpath e o mesmo caminho, por isso roda aqui, na imagem final.
# O perfil aot-train (application-aot-train.properties) e de producao e deve desligar datasource,
# Flyway e Kafka no startup; o PR que trouxer o datasource acrescenta as exclusoes ali. O perfil
# noop e so de teste.
RUN java -XX:AOTCacheOutput=/app/app.aot -Dspring.context.exit=onRefresh \
      -Dspring.profiles.active=aot-train -jar /app/app.jar \
 && chmod 0644 /app/app.aot

# Logs em JSON no console (perfil json); SPRING_PROFILES_INCLUDE soma ao perfil ativo em vez de
# ser substituido quando o compose define SPRING_PROFILES_ACTIVE.
ENV SPRING_PROFILES_INCLUDE=json
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
