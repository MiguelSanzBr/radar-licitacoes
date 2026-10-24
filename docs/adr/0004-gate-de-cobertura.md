# ADR-0004: Cobertura de 100% como gate e política de exclusões

- Status: aceita
- Data: 2026-10-11

## Contexto

Um número de cobertura só tem valor se não puder ser contornado. Exclusões amplas, como pacotes inteiros fora da medição, deixam de fora justamente o código mais arriscado: persistência, mensageria e HTTP. O gate precisa ser exigente e ao mesmo tempo ter uma regra clara para exceções.

## Decisão

- JaCoCo com `report` e `check` em cada módulo: 100% de linha e de ramo por módulo (elemento `BUNDLE`). A ideia inicial era um `report-aggregate` do projeto todo; o gate ficou por módulo, que é mais exigente, porque o código de um módulo não compensa o de outro.
- Unidade e integração contam juntas. Código de adapter é coberto pelos testes de integração com Testcontainers, não excluído.
- Exclusões só para código gerado (openapi-generator, jOOQ codegen e Avro). Quando a anotação `@Generated` do gerador sobrevive à compilação, o JaCoCo a respeita sozinho; quando não sobrevive (retenção `SOURCE`, caso do openapi-generator), a exclusão é por caminho do pacote gerado. Não há exclusão de pacote escrito à mão.
- `scripts/check-coverage-policy.sh` falha se o `pom.xml` tiver um `<exclude>` que não esteja listado em `docs/coverage-exclusions.md` com justificativa. Toda exclusão exige entrada com justificativa em `docs/coverage-exclusions.md`, e `scripts/check-coverage-policy.sh` falha sem ela; a mudança passa pelo PR como qualquer outra.
- O `main()` da aplicação é coberto por teste que chama `RadarApplication.main` com `--spring.main.web-application-type=none --spring.profiles.active=noop`.
- PIT com limiar de mutação de 85% nas primeiras 4 semanas e 90% depois. O PIT é o contrapeso da cobertura morta: teste que passa pelo código sem assert não mata mutante.
- Proibidos `@SuppressWarnings` em teste e `assertTrue(true)`. Uma regra ArchUnit sobre os testes e uma regra SpotBugs verificam isso.

## Alternativas rejeitadas (e por quê)

- Excluir adapters ou pacotes inteiros: rejeitado porque esconde o código de persistência, Kafka e HTTP, que é onde mais erra.
- Gate de 80%: rejeitado porque deixa uma faixa fixa de código sem teste, que tende a crescer com o tempo.
- Cobertura sem PIT: rejeitado porque um teste pode executar o código sem nenhum assert e ainda assim dar 100%.

## Consequências

- Os PRs ficam mais lentos, porque todo código novo precisa de teste no mesmo PR. O TDD visível (teste primeiro) reduz o retrabalho.
- O job `verify` fica mais pesado. Os containers são compartilhados por JVM (singleton) para conter o custo no CI.
- Mudar o limiar do PIT é um PR de `chore` com justificativa, não uma decisão silenciosa.

## Prova

- Job `verify` do CI: JaCoCo `check` com linha e ramo em 100%.
- Job `mutation` do CI: PIT com limiar de 85%.
- Script `scripts/check-coverage-policy.sh` e o arquivo `docs/coverage-exclusions.md`.
