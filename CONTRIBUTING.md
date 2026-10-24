# Como contribuir

Este guia vale para qualquer PR, inclusive os de documentação.

## Branches

- `feat/<marco>-<assunto>` para funcionalidade, por exemplo `feat/m1-coleta-incremental`.
- `fix/<assunto>`, `docs/<assunto>`, `chore/<assunto>`, `test/<assunto>` e `perf/<assunto>` para os demais tipos.

`main` é protegida: não há push direto. Toda mudança entra por PR com CI verde, em squash merge.

## Commits

- Conventional Commits, em inglês: `feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `chore:`, `perf:`.
- Autoria única, a configurada no git local. Não usar trailers de coautoria.
- Não pular hooks do git: `--no-verify` é proibido, inclusive com hooks locais de validação.

## TDD visível

1. Primeiro commit `test:` com o teste vermelho.
2. Depois, `feat:` (ou `fix:`) que faz o teste passar.
3. Se necessário, `refactor:`.

O CI avalia o HEAD do PR, então commits vermelhos intermediários são aceitos. O HEAD precisa estar verde.

## Tamanho do PR

Até 400 linhas de diff de produção. Se passar disso, divida o trabalho em PRs menores.

## Definição de pronto

- CI verde: cobertura de 100% de linha e de ramo em cada módulo (o gate é por módulo, não agregado), PIT acima do limiar, ArchUnit, Modulith verify, SpotBugs e Spotless.
- ADR do PR mergeada no mesmo PR ou no anterior (ver `docs/adr/0001-registro-de-decisoes.md`).
- Artefato de prova em `docs/benchmarks/` quando o PR traz número.
- README atualizado quando o PR expõe capacidade nova.

## Idioma

- Identificadores de código em inglês.
- README, ADRs, runbook, post-mortem e demais docs em português do Brasil, em texto direto.

## Comandos

- `make verify`: build completo com testes, cobertura e análise estática. Precisa de Docker: o modelo jOOQ é gerado das migrations num Postgres descartável (Testcontainers).
- `./mvnw test -pl core -am -Dtest=ModularityTest -Dsurefire.failIfNoSpecifiedTests=false -Dmodules.docs.update=true`: regenera `docs/modules/` quando o teste de documentação dos módulos acusar diferença; depois commite os arquivos.
