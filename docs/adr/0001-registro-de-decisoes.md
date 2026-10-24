# ADR-0001: Registro de decisões e o que exige ADR

- Status: aceita
- Data: 2026-10-11

## Contexto

O projeto é desenvolvido por uma pessoa só. Sem registro, o motivo de cada escolha se perde, e a revisão futura depende de lembrança. Também é preciso um critério claro para saber quando uma escolha merece registro e quando é detalhe de implementação.

## Decisão

- Cada decisão vai para `docs/adr/NNNN-titulo.md`, a partir de `docs/adr/template.md`. Os números são sequenciais e não são reaproveitados.
- Exige ADR toda escolha entre alternativas reais com custo: tecnologia, padrão de arquitetura, política de dados, formato de evento, limite numérico ou regra de processo.
- Uma capacidade só é considerada pronta com código, teste ou benchmark e ADR. Sem as três coisas, ela continua em andamento.
- Status possíveis:
  - `proposta`: em discussão, ainda não vale para o código.
  - `aceita`: vale para o código.
  - `substituída por ADR-NNNN`: a decisão foi trocada. A ADR antiga não é reescrita para mudar a decisão. Cria-se uma nova ADR e a antiga recebe o novo status.
- Cortes de escopo são registrados como nota no fim desta ADR, na seção "Notas de escopo". Regra: ao fim de cada marco, se o estouro acumulado passar de 3 dias úteis, aplica-se o próximo corte previsto na ordem de corte do projeto: M9 (camada 4), depois na camada 3: 8.3 Debezium, 8.2 extração, 8.4 mensageria avançada, 8.1 registry; antes disso, os cortes internos de cada marco anotados nesta ADR. Registra-se a nota aqui.

## Alternativas rejeitadas (e por quê)

- Registrar decisões só em issues ou no histórico de commits: a busca é ruim e a decisão some quando a issue é fechada ou o commit é reescrito.
- Registrar todas as escolhas, inclusive as triviais: o volume cresce e as ADRs úteis se perdem no meio.

## Consequências

- Todo PR que faz uma escolha nova traz a ADR no mesmo PR ou no anterior.
- Revisar uma ADR antiga exige nova ADR, o que deixa a trilha de mudanças visível.

## Prova

- Template em `docs/adr/template.md`.
- Regra de pronto em `CONTRIBUTING.md`, seção "Definição de pronto".

## Notas de escopo

Estado em 2026-10-24, ao fim do M8. O M0 ao M8 foram entregues. O que ficou de fora, com o motivo e onde está registrado:

- **M9 (camada 4) não foi feito.** Nenhum gRPC nem GraphQL: era o primeiro corte da ordem de corte e o prazo não deixou folga. A extração em dois serviços (ADR-0028) segue sem canal interno entre eles, porque `search-api` lê o próprio read model. Ver [ADR-0028](0028-extracao-ingestor-e-search-api.md) e a seção "Fora do escopo" do [README](../../README.md).
- **Spring Cloud Contract adiado.** Não há linha de lançamento compatível com o Boot 4.1; o provedor é validado por conformidade com o OpenAPI em todo teste MockMvc. Ver [ADR-0013](0013-contract-first-openapi.md) e [docs/compat-matrix.md](../compat-matrix.md).
- **Sem `release.yml`.** O `main.yml` publica a imagem no GHCR por sha e por tag `v*`, mas não há GitHub Release com jar, SBOM, checksums nem `latest.json` anexado. Ver [ADR-0022](0022-imagem-compose-deploy.md), seção "Deploy e rollback".
- **OpenAPI não publicado no GitHub Pages.** O contrato fica em `contracts/src/main/resources/openapi/radar-api.v1.yaml` e é verificado no CI (spectral e testes de conformidade). Ver [ADR-0013](0013-contract-first-openapi.md).
- **Disponibilidade da API sob caos não medida no compose.** O caos com Toxiproxy prova zero perda e zero efeito duplicado em teste, mas o SLO de 99,5% em 1 h com falhas injetadas não foi medido (a linha do README diz "não medido"). Ver [ADR-0025](0025-observabilidade-e-slos.md), [docs/benchmarks/slo/2026-10-23.md](../benchmarks/slo/2026-10-23.md) e [docs/benchmarks/chaos/2026-10-23.md](../benchmarks/chaos/2026-10-23.md).
- **A sonda do PNCP terminou no 429, sem as 24 h, sem a rampa S3 e sem as horas da madrugada.** Rodou 2 h 42 em 2026-10-22, a 0,5 req/s, e foi encerrada. A taxa final de 0,2 req/s e o recorte são padrões declarados, não medidos. Ver [docs/benchmarks/probe/2026-10-22.md](../benchmarks/probe/2026-10-22.md), [ADR-0005](0005-recorte-e-etiqueta-de-coleta.md) e [ADR-0017](0017-resiliencia-cliente-pncp.md).
- **Sem microbenchmark JMH.** O ponto quente medido era SQL, não código da JVM, e o profile mostrou 99,8% do tempo em espera do JDBC. Ver [ADR-0020](0020-concorrencia-na-coleta.md) e [docs/benchmarks/jvm/profile-before-after.md](../benchmarks/jvm/profile-before-after.md).
- **O chart Helm não tem a forma dividida.** Há um só Deployment (o monólito), e o chart recusa mais de uma réplica enquanto não houver um Deployment por app. Ver [ADR-0023](0023-helm-kind-terraform-localstack.md) e [ADR-0028](0028-extracao-ingestor-e-search-api.md), seção "Fora desta extração".
- **O nightly é só a fumaça da busca.** Roda o S1 por 1 minuto sobre 50 mil linhas: não há PIT completo, backfill real de 1 dia contra o PNCP nem dependency-check (a análise de dependências vem do SBOM com Grype no `ci.yml`). Ver `.github/workflows/nightly.yml` e [ADR-0004](0004-gate-de-cobertura.md).
- **Sem captura de tela de trace no README.** A propagação do `traceId` é provada por teste (`TracePropagationIT`) e descrita na seção Observabilidade; o que o README mostra é o GIF da interface. Ver [ADR-0025](0025-observabilidade-e-slos.md).
- **Sem snapshot do PNCP como asset de release.** O único dado real versionado é o dia de 2026-10-21 da modalidade 6 (1.633 editais, `infra/compose/seed`), e o benchmark completa o volume com massa sintética rotulada. Ver [ADR-0005](0005-recorte-e-etiqueta-de-coleta.md) e [ADR-0022](0022-imagem-compose-deploy.md).
- **Badges não se aplicam.** O repositório é local, sem remoto, então não há CI hospedado nem badge de build, cobertura ou licença para mostrar.
