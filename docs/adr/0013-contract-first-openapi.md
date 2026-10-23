# ADR-0013: Contract-first com OpenAPI, problem+json e Spring Cloud Contract adiado

- Status: aceita
- Data: 2026-10-13

## Contexto

A API REST tem consumidores fora do código (clientes HTTP, a interface web com htmx e, depois, o painel de operação). Se o contrato nascer do código, ele muda sem aviso e a documentação envelhece. Também é preciso um formato único de erro e um jeito barato de provar que cada resposta do servidor obedece ao contrato.

## Decisão

- O contrato é `contracts/src/main/resources/openapi/radar-api.v1.yaml` (OpenAPI 3.1), no módulo Maven `contracts`. Ele fica em `src/main/resources` para viajar no classpath; o módulo também vai receber os schemas JSON dos eventos.
- As interfaces e os DTOs do servidor são gerados com `openapi-generator-maven-plugin` 7.26.0 (gerador `spring`, `interfaceOnly`, `useSpringBoot4`, `useTags`, `skipDefaultInterface`, `openApiNullable=false`, sem anotações de documentação). Os controladores escritos à mão só implementam as interfaces geradas. O código gerado vive em `target/generated-sources/openapi` e está fora do gate de cobertura e do PIT por caminho, listado em `docs/coverage-exclusions.md`.
- Erros usam `application/problem+json` (RFC 9457). O campo `type` aponta para `docs/problems/<código>.md` e cada código usado tem uma página. Validação traz `errors[]` com `field` e `message`.
- Versionamento por caminho (`/api/v1`). `Deprecation` e `Sunset` ficam documentados como reservados até existir uma v2.
- O contrato passa por lint com Spectral (`make lint-openapi`, job `contracts` do CI), com regras próprias: toda operação tem `operationId`, tags e ao menos uma resposta 4xx, e toda 4xx é `application/problem+json`. Aviso do Spectral também derruba o build.
- Todo teste MockMvc de API valida requisição e resposta contra o contrato com `OpenApiConformance.conforms()`, apoiado em `openapi-request-validator-mockmvc` 3.0.0 (o artefato antigo `swagger-request-validator-mockmvc` foi renomeado e redireciona para esse). Essa versão valida corpos OpenAPI 3.1, inclusive `type: [string, "null"]`; um teste do próprio helper prova que resposta sem campo obrigatório e requisição fora do contrato são recusadas.
- Spring Cloud Contract (SCC) fica adiado até haver linha compatível com o Spring Boot do projeto. A validação OpenAPI cobre o lado do provedor.

### Por que SCC foi adiado

Verificado em 2026-10-13 no Maven Central: SCC 5.0.x (a última é a 5.0.3) pertence ao trem Spring Cloud 2025.1.x, construído sobre o Spring Boot 4.0.x (o BOM 2025.1.4 fixa 4.0.9). A linha seguinte, SCC 5.1.0 no trem 2026.0.0, só tem candidata a lançamento (RC1, sobre Spring Boot 4.2.0-RC1). Nenhum trem declara o Spring Boot 4.1, que é o do projeto (4.1.2). Não tentei rodar o SCC 5.0.x sobre o Boot 4.1: sem suporte declarado, qualquer falha futura seria problema nosso e sem resposta do projeto. A revisão acontece quando o trem 2026.0 sair da fase de candidata e a linha entrar em `docs/compat-matrix.md`.

O custo da decisão: não há testes de provedor gerados a partir de contratos YAML nem stubs para consumidores. Hoje o único consumidor é a interface do próprio repositório, então o risco é baixo. O OpenAPI cobre forma, tipos, obrigatoriedade e códigos de status; não cobre exemplos de comportamento (qual corpo exato para qual entrada), que continuam em testes de integração comuns.

## Alternativas rejeitadas (e por quê)

- Code-first com springdoc: o contrato passaria a ser consequência do código e mudaria sem revisão; não há como travar um breaking change no PR.
- Pact: bom para vários consumidores independentes, mas exige broker e disciplina de publicação que não se justificam com um consumidor interno. Reavaliar se surgirem clientes externos com ciclo próprio.
- OpenAPI 3.0.3: a validação de corpo 3.1 funciona com a versão 3.0.0 do validador, então não houve razão para abrir mão de `null` em tipo composto e de `license.identifier`.
- SCC agora, em versão sem suporte declarado ao Boot 4.1: custo de manutenção sem garantia, para um ganho que o OpenAPI já cobre em parte.

## Consequências

- Mudar a API é mudar o YAML primeiro: o build regenera as interfaces e quebra a compilação de quem não acompanhou.
- O módulo `core` depende do módulo `contracts` e lê o YAML por caminho relativo (`../contracts`) na geração; o jar de `contracts` leva a mesma cópia ao classpath dos testes.
- O `id` de licitação na rota troca a barra do `numeroControlePNCP` por hífen, porque `%2F` em segmento de caminho é rejeitado por contêineres de servlet por padrão. O servidor precisa fazer a conversão inversa, e só o último hífen volta a ser `/` (os demais fazem parte do número, como em `CNPJ-1-SEQ`).
- Toda rota nova exige respostas 4xx em `problem+json`, senão o lint falha.

## Prova

- `ProcurementsControllerTest` (`@WebMvcTest`): toda resposta e toda requisição válida passam por `OpenApiConformance.conforms()`; as requisições inválidas de propósito usam `answerConforms()`, que valida só a resposta. Cobre 304 com `If-None-Match` na lista (ETag fraco) e no detalhe (ETag forte), 200 com tag diferente, ETag estável para a mesma página, `Link` só com `nextCursor` e problem+json em 400 (cursor, `uf`, `limit`, `status`), 404 e 422.
- `StatsControllerTest` e `OrganControllerTest`: as duas rotas contra o contrato, com 400 `validation` para `groupBy` ou `q` ausente, inválido ou em branco.
- `DeprecationInterceptorTest`: as rotas marcadas com `@DeprecatedApi` enviam `Deprecation` e `Sunset`; as demais não. Nenhuma rota real usa a anotação (a API é v1).
- `ProcurementsRestIT`: busca, paginação pelo cabeçalho `Link`, detalhe com ETag, 304, histórico, estatísticas e órgãos contra o Postgres real, com o limite de requisições ligado.
- O ETag da lista é `W/"<sha256 do corpo serializado>"`; o do detalhe é o `etag` da `procurement_detail_view`, entre aspas.
