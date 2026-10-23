# Cache do detalhe: taxa de acerto e latência

Data: 2026-10-23. Commit: `e774b53`. JDK 25.0.5+8. Máquina: AMD Ryzen 5 5600GT (12 threads), 11 GiB de RAM, Linux 6.12. Postgres 18 e Redis 8.8 em contêineres locais (Testcontainers), cliente na mesma máquina.

## Como medir

```
./mvnw -Pbench verify -pl core -am -Dtest=NoSuch -Dit.test=DetailCacheBenchIT -Dsurefire.failIfNoSpecifiedTests=false
```

O teste (`@Tag("bench")`, fora do `verify`) projeta 2 000 detalhes pelo `ProjectionStore` de verdade e faz 20 000 leituras pelo `DetailUseCase`, com ids numa distribuição de tipo Zipf (expoente 1, semente fixa: poucos ids concentram a maior parte das leituras). A mesma sequência roda sem cache (direto no `JooqDetailReadModel`) e com cache (`RedisDetailCache`, TTL de 1 h). Antes de medir, cada modo faz 2 000 leituras de aquecimento e o cache é esvaziado: o preenchimento entra na medição. Uma thread, leituras em série, sem HTTP. A tabela sai em `core/target/detail-cache-bench.md`.

## Resultado

| modo | p50 (µs) | p95 (µs) | p99 (µs) | média (µs) | tempo total (s) |
|---|---|---|---|---|---|
| sem cache | 110 | 152 | 183 | 114 | 2,28 |
| com cache | 103 | 434 | 630 | 143 | 2,87 |

- Leituras: 20 000. Acertos: 18 204. Faltas: 1 796. **Taxa de acerto: 91,0%** (hit / (hit + miss)). Cada falta guardou uma chave: 1 796 chaves no Redis ao final.
- Tamanho: 793 bytes de valor por chave em média (o JSON do detalhe com o histórico de 1 situação) e cerca de 1 019 bytes de memória do Redis por chave (`used_memory`, que inclui chave, estrutura e alocador). Com 64 MB de teto, isso é da ordem de 60 mil detalhes antes de a política `volatile-ttl` começar a descartar; a ADR-0027 usa esse número no orçamento.
- Uma execução, sem repetição nem desvio padrão.

## Leitura honesta

- **Neste cenário o cache não reduz a latência de uma leitura.** O Postgres responde uma busca por chave primária em cerca de 0,1 ms na mesma máquina, o mesmo que um GET no Redis mais a leitura do JSON. O p50 empata; o p95 e o p99 do modo com cache são piores porque 9% das leituras são faltas, que pagam o GET, a consulta ao Postgres e o SET (cerca de 0,4 a 0,6 ms). O custo é pequeno frente ao SLO do plano (detalhe com p95 de até 50 ms), que os dois modos cumprem com folga.
- O ganho esperado do cache está em tirar leituras repetidas do Postgres (CPU, conexões do pool da leitura e o `statement_timeout`) quando há concorrência e a base está ocupada com a projeção e a busca. Isso este teste não mede: é uma thread, sem carga em volta.
- O Gatling S5 (100 usuários, 80% de ids repetidos) vem com a PR 2.7 e é quem mede o efeito sob carga, com latência fim a fim por HTTP. Até lá, este documento só sustenta a taxa de acerto de uma distribuição concentrada e o tamanho por chave.
