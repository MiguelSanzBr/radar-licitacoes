# Benchmark da busca depois da otimização guiada por perfil (PR 6.3)

Mesma massa do `docs/benchmarks/search/2026-10-23/`: 1.200.000 linhas na visão de busca, das quais 1.633 reais (PNCP, 2026-10-21) e 1.198.367 sintéticas (`tools/synth`, mesma semente). **Inclui dados sintéticos (1.198.367 linhas).** Mesma máquina, mesmo compose de benchmark, mesmos cenários e usuários. Uma execução por cenário, 1 min de aquecimento descartado e 3 min medidos.

Reproduzir (a pilha precisa partir de volume vazio, `CLEAN=1 make down`):

```
make image
BENCH_DATE=AAAA-MM-DD BENCH_SCENARIOS="s1 s2 s4 s5" BENCH_EXPLAIN=0 BENCH_KEEP=1 make bench
RANK_CANDIDATES=2000 scripts/explain/run.sh after docs/benchmarks/search/AAAA-MM-DD/explain
scripts/relevance.sh > docs/benchmarks/search/AAAA-MM-DD/relevance.txt
```

## O que mudou

1. **S1, ranking limitado (ADR-0011).** A busca textual ordena as linhas que casam por data de publicação, fica com as `radar.search.rank-candidates` mais recentes (padrão 2.000) e só então calcula o `ts_rank_cd` e pagina. Termo comum deixa de pontuar 400 mil linhas.
2. **S1, plano por valor.** As conexões da busca passam a rodar com `plan_cache_mode = force_custom_plan`. Depois de cinco execuções o driver prepara o comando no servidor e o Postgres podia guardar um plano genérico: o genérico escolhe o GIN para todo termo (o que é o pior caso do termo comum). Ver "Primeira rodada" abaixo.
3. **S4, tabela `search.organ` (V16).** Uma linha por nome de órgão, mantida pelo projetor na mesma transação da visão. A busca de órgão lê 1.539 linhas, não 1,2 milhão.

## Resultados

Fase medida de 3 min.

| Cenário | Usuários | Requisições | Erros | p50 (ms) | p95 (ms) | p99 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 busca textual | 50 | 8.662 | 0 | 34 | 83 | 122 |
| S2 busca com UF, modalidade e `open=true` | 50 | 8.925 | 0 | 6 | 22 | 46 |
| S4 órgão com erro de digitação | 20 | 3.545 | 0 | 13 | 18 | 23 |
| S5 detalhe (rodada 1, ver abaixo) | 100 | 17.975 | 0 | 1 | 2 | 5 |

### Antes e depois (mesma massa, mesmo cenário)

| Cenário | PR 2.7 (`2026-10-23/`) | Sem o teto, com plano por valor | Primeira rodada desta PR | Final |
| --- | --- | --- | --- | --- |
| S1 p50 / p95 / p99 (ms) | 1.005 / 3.402 / 5.857 | 1.048 / 3.677 / 5.108 | 285 / 2.230 / 3.319 | **34 / 83 / 122** |
| S1 erros | 142 de 3.943 | 87 de 3.857 | 69 de 5.804 | **0 de 8.662** |
| S2 p50 / p95 / p99 (ms) | 8 / 44 / 204 | não medido | 6 / 29 / 90 | **6 / 22 / 46** |
| S4 p50 / p95 / p99 (ms) | 4.393 / 5.025 / 5.526 | não medido | 9 / 12 / 15 | **13 / 18 / 23** |
| S4 erros | 544 de 720 | não medido | 0 de 3.567 | **0 de 3.545** |

- "Sem o teto" é a mesma imagem com `RADAR_SEARCH_RANK_CANDIDATES=2147483647` (`BENCH_RANK_CANDIDATES`): o candidato passa a ser todo o conjunto que casa, o que reproduz o custo do ranking sem limite. Estatísticas em `gatling/nocap/`.
- "Primeira rodada" teve o teto e a tabela de órgãos, mas ainda com o plano genérico do driver: S1 melhorou (p95 de 3.402 para 2.230 ms) e continuou acima do alvo, com 69 erros. `pg_stat_statements` mostrou a consulta do S1 com média de 289 ms e 74 milhões de blocos lidos em 6.655 chamadas (11 mil blocos por chamada). Explicação e correção na seção abaixo. Estatísticas em `gatling/first-run/`.
- "Final" é a rodada com o teto e `force_custom_plan`. O S5 não depende do que mudou e vem da primeira rodada (19.518 acertos e 4.444 faltas no cache); S1, S2 e S4 da rodada final.
- Uma rodada à parte do S1 com a gravação JFR ligada (perfil em `docs/benchmarks/jvm/`): p50 35, p95 80, p99 113 ms, 0 erro em 8.656 (`gatling/jfr/`). A diferença para a rodada final é ruído.

### Por que a primeira rodada ainda errava

Com o plano genérico, `search_vector @@ $1` não tem constante para estimar: o Postgres assume cerca de 6 mil linhas e escolhe o GIN com ordenação por data para todo termo. Para o termo comum, isso lê 400 mil linhas do heap (cerca de 11 mil blocos por chamada nas medições). O plano por valor decide pelo termo: termo raro usa `psv_fts_gin`, termo comum anda por `psv_published_keyset` e para ao achar os 2.000 candidatos.

## EXPLAIN (mesma massa, com índices)

Planos em `explain/`. `explain/nocap/` repete S1 e S2 com o teto desligado. "Antes" é a coluna "depois dos índices" de `docs/benchmarks/search/2026-10-23/README.md`.

| Consulta | Antes (ms) | Agora (ms) | Buffers antes | Buffers agora | Nó que lê a visão |
| --- | ---: | ---: | --- | --- | --- |
| `s1-text-common` | 602,8 | **7,2** | acertos=161028 leituras=106269 | acertos=3497 | Index Scan using psv_published_keyset (3.403 buffers, 2.000 linhas) |
| `s1-text-common` sem o teto (mesma forma) | | 812,6 | | acertos=155973 leituras=111252 | Sort externo em disco de 26 MB, 403.237 linhas |
| `s1-text-rare` | 6,6 | 5,8 | acertos=949 | acertos=1044 | Bitmap Heap Scan sobre `psv_fts_gin` |
| `s2-text-filters` | 71,5 | 59,5 | acertos=53078 | acertos=52764 | Index Scan using psv_open_closing |
| `s4-organ-typo` | 2.038,7 | **10,9** | acertos=31965 leituras=103816 | acertos=148 | Seq Scan on organ (1.539 linhas) |

- O termo comum lê 2.000 linhas do índice de publicação (3.959 descartadas pelo filtro) e pontua só essas: nenhum nó do plano devolve mais de 2.000 linhas. É o que o `SearchRankCandidatesIT` afirma.
- O S2 não mudou de caráter: o filtro `open=true` já cortava o conjunto pelo `psv_open_closing` e o teto de 2.000 nunca foi o limite.
- No S4 o planejador escolhe varredura sequencial na tabela de órgãos: com 1.539 linhas ela custa menos que sondar o índice trigrama. O índice `organ_name_trgm` existe para a tabela crescer (o `JooqOrganReadModelIT` mostra o plano com varredura sequencial desligada e 20 mil nomes). O índice `psv_organ_trgm` da visão de busca continua criado: removê-lo é passo de contração do ADR-0010, fica para depois.

## `pg_stat_statements`

`pg_stat_statements.md`, zerado antes do S1 e lido depois do S4. A consulta do S1: 11.454 chamadas, média de 37 ms, máxima de 910 ms (era 4.296 chamadas, média de 403 ms e máxima de 15,3 s). A de órgãos: 4.725 chamadas, média de 11,6 ms e máxima de 23 ms (era média de 1,1 s e máxima de 3,0 s, o `statement_timeout`). O `pg_stat_statements` de S5 e S6 não foi refeito.

## Custo de relevância do teto

`relevance.txt`, gerado por `scripts/relevance.sh`: para cada um dos 200 termos do S1 (`perf/src/test/resources/terms.csv`, tirados de objetos e órgãos reais), compara o top 20 exato com o top 20 sobre os N candidatos mais recentes.

| N | Termos com o teto atuando | Acertos médios no top 20 | Razão média da soma dos ranks |
| ---: | ---: | ---: | ---: |
| 500 | 199 | 2,8 | 0,677 |
| 2.000 | 199 | 6,4 | 0,820 |
| 5.000 | 145 | 7,4 | 0,876 |

Leitura: nesta massa, em que 199 dos 200 termos casam com mais de 2 mil linhas (o texto sintético sai de n-gramas do corpus real, então todo termo real aparece em milhares de linhas), o teto de 2.000 troca cerca de dois terços do top 20 exato por linhas de rank próximo (a soma dos ranks do top 20 fica em 82% da exata). Os ranks do `ts_rank_cd` têm muitos empates em objetos curtos, o que explica por que os acertos caem mais que a razão. Dobrar N de 2.000 para 5.000 recupera pouco (6,4 para 7,4) e custa linhas a mais por busca. Nas 1.633 linhas reais o teto nunca atua (todo termo casa com menos de 2.000). O ADR-0011 registra a decisão.

## Limites desta medição

- Uma execução por cenário, na máquina do desenvolvedor, que ficou compartilhada com o ambiente gráfico (o script de benchmark exigiu 4,5 GB livres, não os 6 GB do padrão). Sem desvio entre execuções.
- O antes do S1 e do S4 vem de `docs/benchmarks/search/2026-10-23/` (mesma massa e semente, medição de outra execução); só a coluna "sem o teto" foi medida de novo com a imagem desta PR.
- A massa sintética sorteia a data de publicação sem relação com o texto: a recência não correlaciona com a relevância, que é a hipótese neutra para medir o custo do teto. Dados reais podem ficar melhores (recência e relevância costumam andar juntas) ou piores.
- A pilha foi reconstruída do zero para esta rodada: o volume anterior não existia mais.
