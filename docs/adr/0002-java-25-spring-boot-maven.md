# ADR-0002: Java 25, Spring Boot 4.1 e Maven multi-módulo

- Status: aceita
- Data: 2026-10-11

## Contexto

O projeto precisa de uma base estável, com suporte de longo prazo e ferramentas de qualidade (cobertura, mutação, análise estática e SBOM) que funcionem juntas no build. A escolha precisa ser feita antes do primeiro módulo, porque mudar de versão de Java ou de sistema de build depois custa retrabalho em todo o projeto.

## Decisão

- Java 25 (LTS).
- Spring Boot 4.1.2 como parent, com Spring Modulith 2.1.2.
- Maven multi-módulo com o wrapper `./mvnw` versionado (Maven 3.9.x).

## Alternativas rejeitadas (e por quê)

- Java 21: Java 25 é a LTS atual e traz recursos usados no domínio e na coleta (records, sealed, pattern matching, virtual threads maduras).
- Gradle: o pipeline (JaCoCo agregado, PIT incremental, Spotless, SpotBugs, CycloneDX) está planejado sobre Maven multi-módulo e o wrapper fixa a versão.

## Consequências

- `StructuredTaskScope` continua em preview no JDK 25 (JEP 505), então não é usado. O uso de virtual threads na coleta está na ADR-0020, a ser escrita.
- O wrapper não instala o JDK. Quem clona o projeto precisa de JDK 25 instalado.
- Atualizar Spring Boot, Spring Modulith ou PIT exige checar a compatibilidade entre Boot e Modulith, e entre PIT e o JUnit em uso (matriz em `docs/compat-matrix.md`).

## Prova

- `./mvnw verify` roda com JDK 25.0.5 e passa no job `verify` do CI.
