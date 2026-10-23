# Perfil da JVM no S1: antes e depois (PR 6.3)

Data: 2026-10-23. JDK 25.0.5 (JRE da imagem do compose), Postgres 18.6, mesma massa de 1.200.000 linhas do benchmark da busca (**inclui dados sintéticos, 1.198.367 linhas**). Máquina e carga de `docs/benchmarks/search/2026-10-23-pr63/README.md`.

## Método

- **Ferramenta:** JFR do próprio JDK 25 (perfil `profile`), sem async-profiler (não há download fixado offline). O renderizador do gráfico de chamas é `scripts/jfr/FlameGraph.java` (arquivo único, `java scripts/jfr/FlameGraph.java`, usa só `jdk.jfr.consumer`), que grava o SVG.
- **Gravação:** a aplicação do compose sobe com `BENCH_JAVA_OPTS="-XX:FlightRecorderOptions=stackdepth=96 -XX:StartFlightRecording=name=s1,settings=profile,jdk.ExecutionSample#period=10ms,jdk.SocketRead#threshold=1ms"` e o S1 do Gatling (50 usuários, 1 min de aquecimento e 3 min medidos) roda por cima. Ao fim: `jcmd <pid> JFR.dump name=s1 filename=/tmp/s1.jfr` dentro do contêiner e `docker cp`.
- **Dois eventos entram, cada um pesado em milissegundos:** `jdk.ExecutionSample` (CPU Java, 10 ms por amostra) e `jdk.SocketRead` (a duração da leitura do socket, a partir de 1 ms). O segundo é o que mostra o tempo esperando o Postgres: as threads de requisição do Tomcat são virtuais (ADR-0020), e uma thread virtual que espera o socket sai do carrier, então nenhuma amostra de CPU a vê. Só entram pilhas que passam por `JooqSearchReadModel`.
- **Antes:** a mesma imagem com `RADAR_SEARCH_RANK_CANDIDATES=2147483647` (`BENCH_RANK_CANDIDATES`), que faz o candidato ser todo o conjunto que casa, ou seja, o ranking sem limite. **Depois:** o padrão, 2.000. Nos dois, plano por valor ligado.

## Resultado

| | Antes (sem teto) | Depois (2.000) |
| --- | ---: | ---: |
| Requisições do S1 na gravação (aquecimento incluído) | 4.935 | 11.464 |
| Espera de socket em `JooqSearchReadModel` | 2.349.804 ms | 443.745 ms |
| por requisição | 476 ms | 39 ms |
| CPU Java amostrada na mesma pilha | 5.460 ms | 4.890 ms |
| por requisição | 1,1 ms | 0,4 ms |
| S1 medido: p50 / p95 / p99 | 1.048 / 3.677 / 5.108 ms, 87 erros | 35 / 80 / 113 ms, 0 erro |

Gráficos: `profile-before.svg` e `profile-after.svg` (gráfico de chamas em forma de icicle: a raiz no topo, a largura é o tempo em ms; o azul é a espera de socket).

## Onde o tempo vai

- **O tempo da requisição é espera do JDBC.** Antes, 99,8% do peso das pilhas da busca é `[JDBC wait: socket read]` (2.349.804 de 2.355.264 ms). A parte Java (jOOQ, mapeamento, Jackson, Tomcat) soma 1 ms por requisição: não há ponto quente na JVM para otimizar, e por isso não há microbenchmark JMH nesta PR (a decisão está no ADR-0020).
- **O ponto quente é o SQL**, que o `EXPLAIN` já tinha mostrado: ordenar 400 mil linhas por `ts_rank_cd` no termo comum. A correção é do lado do banco (ranking de 2.000 candidatos) e a espera de socket cai de 476 para 39 ms por requisição, uma queda de 12 vezes, com a CPU Java no mesmo patamar.
- **Limite do método:** o JFR amostra no máximo cinco threads Java por período, então a CPU Java é um piso, e a espera de socket conta cada leitura de 1 ms ou mais (requisições com mais de uma leitura somam todas). O valor serve para comparar antes e depois sob a mesma carga, não como medida absoluta.
