# ADR-0020: concorrência na coleta

- Status: proposta (virtual threads na coleta no PR 6.1, perfil da JVM no PR 6.3 e coletor de lixo no PR 6.2, todos implementados)
- Data: 2026-10-17

Esta ADR cobre a concorrência da coleta: `StructuredTaskScope` (decidido) e virtual threads com bulkhead (PR 6.1, abaixo). As partes de GC (Serial, G1 ou ZGC) e de otimização guiada por profile foram acrescentadas na M6 (PR 6.2 e 6.3), com dados.

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

- PR 6.1 `perf(jvm): virtual threads in ingestion`: comparação de threads de plataforma e virtuais com WireMock a 200 ms e detecção de pinning, em `docs/benchmarks/jvm/virtual-threads.md` (feito).
- Verificação inicial (PR 0.3): a coleta não depende de nenhuma API em preview; `docs/compat-matrix.md` registra o build sem `--enable-preview` no JDK 25.0.5.

## Virtual threads na coleta (PR 6.1)

**O limitador de taxa é o teto real.** O PNCP respondeu 429 a 0,5 req/s sustentados, e os testes de campo mostraram respostas de 3 a 10 s. Por isso o paralelismo só serve para esconder latência: a taxa em requisições por segundo continua sendo a do `RateLimiter` (padrão 0,2 req/s, ADR-0017), que cada tentativa atravessa antes do bulkhead. Com 0,2 req/s o paralelismo quase não muda o tempo total do backfill; o ganho aparece quando a taxa sobe (a sonda decide) e a latência passa de 1/taxa.

**Como funciona.** `PageFetcher` (`ingest.application`) lê as páginas de uma janela com `Executors.newVirtualThreadPerTaskExecutor()`: depois da primeira página (que diz quantas restam) mantém até `radar.ingest.fetch-ahead` páginas (padrão 2, nunca mais que `radar.pncp.max-concurrent-calls`) em voo ou esperando a vez, e `next()` devolve sempre na ordem das páginas. O processamento (uma transação por página) segue estritamente em ordem, na thread de quem chama, então `cursor_page`, a retomada e as garantias de atomicidade não mudam. O bulkhead do Resilience4j, dentro do gateway, continua sendo o limite de chamadas simultâneas; a profundidade da leitura adiantada é limitada a esse número, porque mais tarefas só ficariam na fila dele, e vale 2 por padrão porque cada página em voo ocupa uma vaga na fila do limitador (5 s por permissão a 0,2 req/s; ADR-0017, permissões reservadas não voltam quando a leitura é cancelada). Vale para backfill e sincronização incremental. Falha de uma página adiantada aparece quando a ordem chega nela (as anteriores já foram confirmadas); ao sair, a leitura interrompe o que ainda está em voo.

**`spring.threads.virtual.enabled=true`** vale para o app todo. Verificado (o agendador em `BackfillIT`; Tomcat e Kafka com sondas descartáveis, não versionadas):

- Tomcat atende as requisições em threads virtuais (`tomcat-handler-0` é virtual).
- `@Scheduled`: o agendador virtual do Boot (`SimpleAsyncTaskScheduler`) roda todas as tarefas de atraso fixo numa só thread de plataforma, então uma tarefa lenta pararia o relay do outbox. O app declara o próprio `ThreadPoolTaskScheduler` (`RadarConfiguration`, 4 threads `radar-sched-`, `spring.task.scheduling.pool.size`), que substitui o do Boot; Tomcat e Kafka continuam virtuais. `BackfillIT` prova que uma tarefa bloqueada não para outra.
- Ouvintes Kafka: **diferente do que se supunha, não ficam em threads de plataforma.** O Boot, com threads virtuais ligadas, configura o executor dos containers criados por `ConcurrentKafkaListenerContainerFactoryConfigurer` como virtual: os ouvintes `alerts-matcher` e `alerts-notifier` rodam em threads virtuais. O projetor de busca monta a própria fábrica sem o configurer, então fica em thread de plataforma. Nada disso foi mudado: rodando os ITs de Kafka, outbox e coleta sob JFR não houve pinning. Atenção: o laço de `poll()` de cada container é uma tarefa longa e ocupa uma thread de carrier do `ForkJoinPool` por container enquanto bloqueia sem desmontar a thread virtual (Selector) (o consumidor do Kafka alterna entre `poll` e processamento); com poucos containers isso é irrelevante, mas muitos containers somados a muita carga de CPU podem esgotar os carriers (`jdk.virtualThreadScheduler.parallelism`, padrão = núcleos). Se isso aparecer, o remédio é pôr os containers em threads de plataforma (`setListenerTaskExecutor`).

O pool de agendamento (4 threads de plataforma) atende sete jobs. Se coleta incremental, fechamento de expiradas, backfill e relay bloquearem ao mesmo tempo, os jobs por cron (resumo diário, expurgo) esperam a próxima thread livre; era assim antes desta mudança e o remédio é aumentar `spring.task.scheduling.pool.size`.

**Pinning.** A regra de ArchUnit (`IoPathHasNoSynchronizedTest`) proíbe método `synchronized` em `..ingest.adapter.out.pncp..` e `..ingest.application..`; blocos `synchronized` são pegos por varredura de fonte, porque o ArchUnit não enxerga blocos. No JDK 25 (JEP 491) monitores já não prendem a thread virtual ao carrier, então a regra é preventiva (ainda vale para inicialização de classe e frames nativos, que prendem). A medição fica em `-Pjfr`: o failsafe roda sob uma gravação JFR com `jdk.VirtualThreadPinned` a partir de 20 ms (com `stackdepth=256`) e o teste de `BackfillIT` lê o snapshot (`RecordingFile`) e falha se algum pino de 20 ms ou mais passar por código de `ingest`. Um teste de unidade prova que o detector enxerga um pino real (inicializador de classe que bloqueia). Resultado em `docs/benchmarks/jvm/virtual-threads.md`.

**Por que não `StructuredTaskScope`** continua como decidido acima: preview no JDK 25 e 26, contaminaria o build. O cancelamento fica explícito (`Future.cancel(true)` ao fechar a leitura e fechamento do executor), coberto por teste.

**Medição.** Com 200 páginas de 200 ms, taxa alta só no benchmark, virtual com bulkhead N empata em tempo com um pool de plataforma de N threads e dispensa o pool: o bulkhead fixa a concorrência e as threads virtuais ficam fora da contagem de threads de plataforma, mesmo com 200 tarefas esperando vaga (um pool de plataforma de 200 threads dá o mesmo tempo com 200 threads a mais). Tabela e método em `docs/benchmarks/jvm/virtual-threads.md`.

## Perfil e otimização (PR 6.3)

Perfil JFR da JVM durante o S1 do benchmark da busca (1,2 milhão de linhas, 50 usuários), antes e depois de limitar o ranking (ADR-0011, "Perfil e otimização"). Método, gráficos de chamas e números em `docs/benchmarks/jvm/profile-before-after.md`.

- **O tempo da requisição é espera do JDBC.** Antes, 99,8% do peso das pilhas da busca era leitura de socket esperando o Postgres (476 ms por requisição); a CPU Java na mesma pilha ficou em 1,1 ms por requisição. Depois, a espera caiu para 39 ms por requisição, e a CPU Java para 0,4 ms.
- **As threads virtuais mostram o que as amostras de CPU escondem.** As requisições do Tomcat rodam em threads virtuais (PR 6.1): uma thread virtual que espera o socket sai do carrier, e o `jdk.ExecutionSample` não a vê. O perfil usa `jdk.SocketRead` para a espera, pesado pela duração.
- **Não há ponto quente na JVM.** O ponto quente era o SQL (ordenar 400 mil linhas por `ts_rank_cd`), corrigido no banco. Por isso esta PR não traz um microbenchmark JMH: não há trecho Java que o justifique, e a regressão é guardada pelo plano (`SearchRankCandidatesIT`) e não pelo relógio. Se um perfil futuro achar um ponto quente na JVM, o JMH entra com ele.
- **Ferramenta.** JFR do próprio JDK 25 e `scripts/jfr/FlameGraph.java` (arquivo único que lê a gravação e escreve o SVG); async-profiler não foi usado porque não há download fixado offline.

## Coletor de lixo (PR 6.2)

**Decisão: G1, fixado em `JAVA_TOOL_OPTIONS` da imagem (`-XX:+UseG1GC`), sob o `mem_limit` de 640 MB do compose.** Antes, o app rodava Serial sem que ninguém tivesse escolhido: sob 1792 MB de memória a ergonomia da JVM cai no Serial, e o G1 só entra acima disso. Fixar a opção impede que trocar o limite do container mude o coletor em silêncio.

Medição (`make profile GC=serial|g1|zgc`, `docs/benchmarks/jvm/gc.md`): 201.633 linhas (200 mil sintéticas rotuladas mais o dia real), Gatling S1 com 50 usuários (1 min de aquecimento e 3 min medidos) e replay de 201.738 eventos, a mesma massa e o mesmo container de 640 MB para os três.

| | Serial | G1 | ZGC (geracional) |
| --- | ---: | ---: | ---: |
| S1 p99 (ms) | 24 | 27 | 45 |
| S1 pausa máx (ms) | 12,6 | 13,9 | 0,44 |
| Replay vazão (eventos/s) | 659 | 724 | 653 |
| Replay pausa máx (ms) | 466,8 | 11,9 | 162,7 |
| CPU no S1 (s em 4 min) | 47,5 | 57,4 | 52,4 |
| RSS de pico após o replay (MiB) | 653 | 592 | 659 |

- **O Serial para por quase meio segundo** (GC completo sob pressão de alocação, 467 ms; 8 GCs completos na execução). O G1 não fez GC completo e ficou em 14 ms no máximo.
- **O ZGC não tem folga em 640 MB:** RSS e `docker stats` no limite, 442 paradas por alocação, p99 de 45 ms no S1 e uma pausa de 163 ms no replay. Com 1 GB as pausas somem (0,29 ms no máximo) e o p99 iguala o do G1, ao custo de uma JVM de 1 GB.
- **O S1 quase não vê o coletor:** a busca é espera do Postgres (PR 6.3), e o rps e os percentis ficam dentro do ruído de uma execução.

**Custo aceito:** o G1 gasta cerca de 21% mais CPU que o Serial (57,4 contra 47,5 s no S1) e 17 MiB a mais de RSS no S1.

**Revisão:** com o limite do app em 1 GB ou mais e p99 como requisito, medir o ZGC geracional de novo; com CPU escassa (por exemplo 0,5 vCPU), medir o Serial de novo. O AOT cache não depende do coletor: o `RadarImageIT` confere o uso do cache com a imagem em G1.
