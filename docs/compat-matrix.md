# Matriz de compatibilidade

Data da verificação: 2026-10-17. JDK 25.0.5+8, Maven 3.9.11 (wrapper), Docker local.

As linhas marcadas como executadas vêm de um projeto descartável (parent `spring-boot-starter-parent` 4.1.2, Java 25) que reuniu todas as dependências abaixo e rodou os testes descritos. O projeto foi apagado ao fim da verificação; os comandos e as mensagens de erro estão registrados aqui. Linhas marcadas como "pesquisa" não foram executadas e devem ser verificadas no marco indicado.

Legenda de resultado: funciona, funciona com contorno, falha, pesquisa.

## Versões resolvidas no teste

| Componente | Versão resolvida | Origem |
|---|---|---|
| Spring Boot | 4.1.2 | parent |
| Spring Modulith | 2.1.2 | BOM `spring-modulith-bom` |
| Hibernate ORM | 7.4.13.Final | gerenciada pelo Boot |
| jOOQ (OSS) | 3.21.9 | gerenciada pelo Boot |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) | gerenciada pelo Boot |
| Driver PostgreSQL | 42.7.14 | gerenciada pelo Boot |
| Spring Kafka / kafka-clients | 4.1.2 / 4.2.2 | gerenciadas pelo Boot |
| JUnit Jupiter / Platform | 6.0.3 | gerenciada pelo Boot |
| Jackson | `tools.jackson` 3.1.7 | gerenciada pelo Boot |
| Testcontainers | 2.0.5 | `testcontainers-bom` |
| PostgreSQL (imagem) | `postgres:18`, servidor 18.6 | Docker |

## Combinações

| Combinação | Versões | Resultado | Evidência | Fallback |
|---|---|---|---|---|
| Spring Boot × Spring Modulith | Boot 4.1.2, Modulith 2.1.2 | funciona | `./mvnw test -Dtest=ContextTest`: `@SpringBootTest` sobe com `ApplicationModules.of(App.class).verify()` passando (2 módulos), 3 testes verdes | Boot 4.0.x ou Modulith 2.2 (`2.2.0-RC1` existe: https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-starter-core/maven-metadata.xml) |
| Contexto completo com PostgreSQL 18 | Boot 4.1.2, `postgres:18` (18.6), Flyway 12.4.0, Hibernate 7.4, jOOQ 3.21 | funciona | mesmo `ContextTest` com `@ServiceConnection`: migração Flyway `V1`, entidade JPA com `@Version` (`ddl-auto=validate`) gravada e lida por jOOQ (`DSLContext.fetchCount`) na mesma base | PostgreSQL 17 (não foi necessário) |
| Hibernate 7 × jOOQ OSS | Hibernate 7.4.13, jOOQ 3.21.9 | funciona | `ContextTest.jpaAndJooqShareTheDatabaseAfterFlyway`: JPA grava, jOOQ lê, mesmo `DataSource`. Geração de código do jOOQ a partir do schema Flyway não foi exercitada (PR 1.9) | |
| Boot × starters renomeados | Boot 4.1.2 | funciona com contorno | `./mvnw dependency:tree` com `spring-boot-starter-aop`: `'dependencies.dependency.version' for org.springframework.boot:spring-boot-starter-aop:jar is missing`. No Boot 4 o starter se chama `spring-boot-starter-aspectj`. Também usar `spring-boot-starter-flyway` e `spring-boot-starter-kafka` | usar os nomes novos |
| JUnit 6 × PIT `pitest-junit5-plugin` | JUnit 6.0.3, pitest-maven 1.30.1, pitest-junit5-plugin 1.2.3 | funciona | `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage` sobre classe mínima (`probe.pit.Calc`): `Generated 3 mutations Killed 2 (67%)`, `2 tests examined`. Com `mutationThreshold` 85 o build falha como projetado (`Mutation score of 67 is below threshold of 85`); com 60 passa (`BUILD SUCCESS`). O mutante vivo é equivalente (`>` para `>=` em `max`) | |
| jqwik × JUnit 6 | jqwik 1.10.2, JUnit Platform 6.0.3 | funciona | `LibsTest`: `@Property` executado pelo engine do jqwik junto do engine Jupiter | |
| ArchUnit × Modulith | archunit-junit5 1.5.1, Modulith 2.1.2 | funciona com contorno | `mvn dependency:tree`: o Modulith traz `archunit:1.4.2` (runtime) e ele vence a mediação sobre o 1.5.1 que o `archunit-junit5` pede. O teste passa assim, mas o núcleo fica desalinhado | declarar `com.tngtech.archunit:archunit:1.5.1` explicitamente no `dependencyManagement`; com isso `LibsTest` e `ContextTest` passam |
| Testcontainers 2.x × módulo PostgreSQL | testcontainers-postgresql 2.0.5 | funciona | `ContextTest` (`org.testcontainers.postgresql.PostgreSQLContainer`, pacote novo do 2.x) | |
| Testcontainers 2.x × módulo Kafka | testcontainers-kafka 2.0.5, imagem `apache/kafka:4.1.0`, kafka-clients 4.2.2 | funciona | `KafkaTest`: cria tópico, produz com `acks=all` e idempotência, consome de volta (1 registro), 12 s | |
| Testcontainers 2.x × módulo Toxiproxy | testcontainers-toxiproxy 2.0.5, `ghcr.io/shopify/toxiproxy:2.12.0`, toxiproxy-java 2.1.11 | funciona | `ToxiproxyTest`: PostgreSQL atrás do proxy numa `Network`, `select 1` pelo proxy, `proxy.disable()` | |
| Testcontainers 2.x × Keycloak | testcontainers-keycloak 4.4.0 (compilado contra Testcontainers 2.0.5), `quay.io/keycloak/keycloak:26.8` | funciona | `KeycloakTest`: `new KeycloakContainer()`, sobe e emite token de admin (66 s, inclui pull) | |
| Resilience4j × Boot 4 | `resilience4j-spring-boot4` 2.4.0 (existe artefato para Boot 4) | funciona | `ContextTest`: `CircuitBreakerRegistry` injetado com o contexto completo. Exige `spring-boot-starter-aspectj` e `spring-boot-starter-actuator` | `resilience4j-spring-boot3` não deve ser usado |
| Togglz × Boot 4 | togglz-spring-boot-starter 4.6.4 | funciona com contorno | primeira execução do `ContextTest`: `jakarta.validation.NoProviderFoundException: Unable to create a Configuration, because no Jakarta Validation provider could be found` ao ligar `TogglzProperties` | adicionar `spring-boot-starter-validation`; depois `FeatureManager` é injetado |
| Spring Cloud Contract × Boot 4.1 | spring-cloud-contract 5.0.3 (trem 2025.1.x, que mira Boot 4.0.9: https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/2025.1.4/spring-cloud-dependencies-2025.1.4.pom; o trem 2026.0.0-RC1 mira Boot 4.2.0-RC1: https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/2026.0.0-RC1/spring-cloud-dependencies-2026.0.0-RC1.pom; versões do verifier: https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-contract-verifier/maven-metadata.xml) | funciona com contorno, sem suporte oficial (só modo MockMvc exercitado) | plugin Maven 5.0.3 gerou `ContractVerifierTest` a partir de `contracts/ping.yml` (modo MockMvc) e o teste passou (`./mvnw clean test -Dtest=ContractVerifierTest`: `Tests run: 1, Failures: 0`). Sem `io.rest-assured:spring-mock-mvc`: `package io.restassured.module.mockmvc does not exist` (nada o gerencia) | adiado: validação OpenAPI cobre o provedor (ADR-0013); Pact como alternativa se o contrato de consumidor for necessário antes do SCC 5.1/Boot 4.2 |
| Spring Kafka × Kafka 4 | spring-kafka 4.1.2, kafka-clients 4.2.2, `apache/kafka:4.1.0` | funciona | `ContextTest` (`KafkaTemplate` injetado) e `KafkaTest` | |
| Extensões do PostgreSQL 18 | `postgres:18` (18.6) com `-c shared_preload_libraries=pg_stat_statements` | funciona | `docker run ... postgres:18`, depois `CREATE EXTENSION` de `unaccent` 1.1, `pg_trgm` 1.6, `pg_stat_statements` 1.12, `pgstattuple` 1.5 e `citext` 1.8: todas retornaram `CREATE EXTENSION`. `unaccent('licitação')` devolve `licitacao`, `similarity('prefeitra','prefeitura') > 0.3` é verdadeiro, `'A'::citext = 'a'::citext` é verdadeiro, `pg_stat_statements` consultável | PostgreSQL 17 (não foi necessário) |
| JDK 25 × JaCoCo | JaCoCo 0.8.15 | funciona | PR 0.1 (`./mvnw verify` no esqueleto) | |
| JDK 25 × PIT | pitest-maven 1.30.1 + pitest-junit5-plugin 1.2.3 | funciona | PR 0.1 (`./mvnw verify` no esqueleto; `./mvnw -Pmutation verify` verde, PIT analisa bytecode do JDK 25) | |
| JDK 25 × SpotBugs | spotbugs-maven-plugin 4.10.5.0 (SpotBugs 4.10.5) | funciona | PR 0.1 (`./mvnw verify` no esqueleto; `BugInstance size is 0`) | |
| JDK 25 × Spotless / google-java-format | Spotless 3.10.4, google-java-format 1.36.0 | funciona com contorno | PR 0.1 (`./mvnw verify` no esqueleto). Com 1.37.0: `InvocationTargetException` opaco dentro do Spotless 3.10.4 (o jar 1.37.0 isolado funciona; incompatibilidade do adaptador do Spotless). 1.35.0 e 1.33.0 também funcionam. 1.28.0 é recusada pelo Spotless (exige 1.30.0 ou mais no JVM 25) | fixar google-java-format 1.36.0 (já no `pom.xml`) |
| JDK 25 × CycloneDX, surefire, failsafe | cyclonedx-maven-plugin 2.9.3, surefire/failsafe 3.5.6 | funciona | PR 0.1 (`./mvnw verify` no esqueleto) | |
| JDK 25 × `StructuredTaskScope` | JEP 505 (JDK 25), JEP 525 (JDK 26) | não usar | continua em preview (https://openjdk.org/jeps/505 e https://openjdk.org/jeps/525); o build não usa `--enable-preview`. Decisão na ADR-0020 | virtual threads + `ExecutorService` + Resilience4j `Bulkhead` |
| Debezium 3.6 × PostgreSQL 18 (`pgoutput`) | Debezium 3.6.x (3.6.3.Final é a mais recente da série; a 3.7.0.Final já saiu) | pesquisa, verificar na M8 | página de lançamentos do Debezium (https://debezium.io/releases/): 3.6 e 3.7 listam testes contra PostgreSQL 14 a 18, e a série 3.4 anuncia "Support for PostgreSQL 18". Documentação do conector (https://debezium.io/documentation/reference/stable/connectors/postgresql.html): `pgoutput` é o plugin padrão do PostgreSQL 10+, sem instalação extra. Nada foi executado | Debezium 3.7; se falhar, PostgreSQL 17 ou manter o relay por polling (ADR-0018) |
| Apicurio Registry 3 × Kafka 4 | Apicurio 3.3.x, kafka-clients 4.2.2 do Boot | pesquisa, verificar na M8 | no Maven Central, `apicurio-registry-avro-serde-kafka` 3.3.3 compila contra `kafka-clients` 3.9.2; com o Boot, o Maven resolveria 4.2.2. A documentação não declara versões de Kafka (https://www.apicur.io/registry/docs/apicurio-registry/3.0.x/getting-started/assembly-installing-registry-docker.html). Pacote de terceiros já distribui Apicurio 3.1 com Kafka 4.1 (IBM Event Streams 12.1). Nada foi executado | travar `kafka-clients` na versão do serde se houver erro; Confluent Schema Registry segue rejeitado por licença (ADR-0024) |

## Contornos que viram requisito de build

1. `spring-boot-starter-validation` quando Togglz estiver no classpath.
2. `com.tngtech.archunit:archunit` 1.5.1 gerenciado explicitamente.
3. `io.rest-assured:spring-mock-mvc` 6.2.0 (teste) ao adotar Spring Cloud Contract no modo MockMvc.
4. Nomes de starter do Boot 4 (`spring-boot-starter-aspectj`, `-flyway`, `-kafka`) e artefato `resilience4j-spring-boot4`.
5. google-java-format 1.36.0 até o Spotless corrigir a 1.37.0.

## Como reproduzir

O projeto de prova não está no repositório. Para refazer: um `pom.xml` com parent `spring-boot-starter-parent` 4.1.2, os BOMs de Modulith 2.1.2, Testcontainers 2.0.5 e `spring-cloud-contract-dependencies` 5.0.3, as dependências desta tabela e os testes citados em "Evidência". Imagens usadas: `postgres:18`, `apache/kafka:4.1.0`, `ghcr.io/shopify/toxiproxy:2.12.0`, `quay.io/keycloak/keycloak:26.8`.

## Renderização dos diagramas

`docs/diagrams/workspace.dsl` foi validado e exportado para PlantUML com `structurizr/cli:2025.11.09`. Essa imagem tem 928 MB sozinha (e a tag `latest` é apenas um aviso de descontinuação, sem comandos), então nenhum PNG foi gerado aqui. A renderização fica para um job de CI.
