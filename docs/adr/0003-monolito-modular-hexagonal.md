# ADR-0003: monólito modular com Spring Modulith e hexagonal por contexto

- Status: aceita
- Data: 2026-10-17

## Contexto

O sistema tem três contextos com cargas diferentes: coleta (I/O externo, agendada), busca (leitura, sensível a latência) e alertas (assíncrono, por evento). Há um único desenvolvedor e prazo de 12 semanas. Separar em serviços desde o início custaria infraestrutura, contratos de rede e testes distribuídos antes de existir domínio para proteger. Por outro lado, um monólito sem fronteiras verificadas degenera em acoplamento por tabela e por classe, e a extração posterior vira reescrita.

## Decisão

Um monólito modular (Spring Boot 4.1.2, Spring Modulith 2.1.2) com um módulo por contexto (`ingest`, `outbox`, `search`, `alerts`, `identity`, `ops`) e arquitetura hexagonal dentro de cada contexto (`domain`, `application`, `adapter`).

- Spring Modulith entra desde o início, com `ApplicationModules.of(RadarApplication.class).verify()` rodando como teste. Não fica para a camada 3.
- Um contexto só acessa outro por evento ou pela API pública do módulo (`@NamedInterface`). `search` nunca lê tabelas de `ingest`; cada contexto tem seu schema no PostgreSQL.
- Regras de ArchUnit: `domain` não depende de Spring, JPA, Jackson, jOOQ nem Kafka; `application` depende só de `domain` e das próprias ports; `adapter` nunca é importado por `domain` ou `application`; nenhum `@Transactional` em adapter de entrada.
- A extração em serviços (`apps/ingestor`, `apps/search-api`) acontece apenas na camada 3 (M8), por razão medida: a ingestão é I/O-bound e agendada, a busca é CPU e latência e escala na horizontal. Até lá existe um único artefato executável (`apps/monolith`).

O diagrama de contexto e de contêineres está em `docs/diagrams/workspace.dsl`.

## Alternativas rejeitadas (e por quê)

- Microsserviços desde o início: multiplica compose, pipelines, contratos e falhas de rede sem benefício antes da carga existir. O custo de teste distribuído competiria com o prazo.
- Monólito em camadas sem módulos verificados: as fronteiras viram convenção e não passam no CI; é o caminho do acoplamento por tabela.
- Módulos Maven separados por contexto: dá isolamento forte de compilação, mas fragmenta o build e o gate de cobertura agregado. Pacotes verificados por Modulith e ArchUnit dão a mesma garantia de dependência com um módulo Maven só (`core`).

## Consequências

- Toda dependência entre contextos precisa de evento ou de interface nomeada, o que dá mais código de fronteira.
- `verify()` e as regras de ArchUnit quebram o build em violação, então o acoplamento é pego no PR.
- A migração para serviços na camada 3 troca o transporte (já é evento) e não o desenho.
- Risco aceito: Spring Modulith 2.1.2 sobre Spring Boot 4.1.2 precisa continuar compatível. Verificado em `docs/compat-matrix.md` (contexto sobe e `verify()` passa); se quebrar numa atualização, o fallback registrado lá se aplica.

## Prova

- Sonda de compatibilidade (esta entrega, PR 0.3): contexto Spring Boot 4.1.2 com Modulith 2.1.2 e `ApplicationModules.verify()` passando, registrada em `docs/compat-matrix.md`.
- PR 1.11 `test(arch): archunit and modulith rules` (`core/src/test/java/br/dev/miguelsanzbr/radar/architecture/`):
  - `ArchitectureTest` aplica as regras ao código de produção: `domain` sem Spring, JPA, Hibernate, Jackson, jOOQ nem Kafka; `application` só com `domain`, `application`, `shared`, `java.*` e `TransactionOperations`; `domain` e `application` nunca importam `adapter`; `search` sem `ingest` nem `outbox` e `ingest` sem `search`; nenhum `@Transactional` (Spring ou Jakarta) em `adapter.in`.
  - `ArchitectureRulesSeeViolationsTest` roda cada regra contra classes que a violam (`architecture/fixture`) e contra classes conformes, então uma regra que deixa de enxergar a violação quebra o build.
  - `SourceHygieneTest` varre `src/test/java` e falha em `@SuppressWarnings` e `assertTrue(true)` (a anotação tem retenção SOURCE, fora do alcance do ArchUnit).
  - `ModularityTest` roda `ApplicationModules.verify()`, confere a lista de módulos, exige `shared` aberto e compara a documentação gerada pelo Modulith com os arquivos commitados em `docs/modules/`. O teste gera os arquivos em `target/` e nunca escreve na árvore de fontes: se houver diferença, ele falha e informa o comando para atualizar (`-Dmodules.docs.update=true`, descrito em `CONTRIBUTING.md`).
- PR 8.2 `refactor(apps): extract ingestor and search-api`: a extração só acontece depois das camadas anteriores, sem alterar os contextos.
