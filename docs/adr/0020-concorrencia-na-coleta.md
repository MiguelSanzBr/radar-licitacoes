# ADR-0020: concorrência na coleta

- Status: proposta
- Data: 2026-10-17

Esta ADR começa só com a decisão sobre `StructuredTaskScope`. As partes de GC (G1 ou ZGC) e de otimização guiada por profile são acrescentadas na M6 (PR 6.2 e 6.3), com dados.

## Contexto

A coleta de páginas do PNCP é I/O-bound e precisa de concorrência limitada: o PNCP tem limite de taxa e a etiqueta de coleta (ADR-0005) fixa o teto de requisições. `StructuredTaskScope` daria concorrência estruturada (cancelamento e propagação de erro no escopo), mas segue como API em preview: JEP 505 no JDK 25 e JEP 525 no JDK 26. Usar preview exige `--enable-preview` em compilação, testes, PIT e execução, e a API mudou entre as prévias.

## Decisão

Não usar `StructuredTaskScope`. A coleta usa virtual threads com `Executors.newVirtualThreadPerTaskExecutor()` e `ExecutorService`, e o paralelismo é limitado por um `Bulkhead` do Resilience4j (junto com o rate limiter, o retry e o circuit breaker da ADR-0017). O cancelamento é explícito: `Future.cancel` e fechamento do executor no fim da janela.

Revisão: reavaliar quando a API sair de preview em uma versão LTS.

## Alternativas rejeitadas (e por quê)

- `StructuredTaskScope` (JEP 505, preview no JDK 25; JEP 525, preview no JDK 26): a API ainda muda, e o preview contaminaria o build inteiro (compilador, surefire, PIT, JaCoCo, imagem). O ganho de cancelamento estruturado não compensa para um fluxo que já tem Bulkhead e timeout.
- Pool de threads de plataforma: limita a concorrência pelo tamanho do pool, mas atrela o paralelismo à memória de pilha e ao bloqueio de I/O; virtual threads dispensam o ajuste.
- Programação reativa (WebFlux): reescreve o modelo do cliente HTTP e o teste, sem necessidade para o volume do PNCP.

## Consequências

- Sem `--enable-preview` em nenhuma parte do build.
- É preciso vigiar pinning de virtual threads (`synchronized` em caminho de I/O); o PR 6.1 inclui regra de ArchUnit e medição com JFR (`jdk.VirtualThreadPinned`).
- O cancelamento e a propagação de erro entre tarefas filhas ficam a cargo do código da coleta, com teste.

## Prova

A ser produzida na M6:

- PR 6.1 `perf(jvm): virtual threads in ingestion`: comparação de threads de plataforma e virtuais com WireMock a 200 ms e detecção de pinning, em `docs/benchmarks/jvm/`.
- Verificação inicial (PR 0.3): a coleta não depende de nenhuma API em preview; `docs/compat-matrix.md` registra o build sem `--enable-preview` no JDK 25.0.5.
