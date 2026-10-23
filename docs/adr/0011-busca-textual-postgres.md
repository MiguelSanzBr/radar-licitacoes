# ADR-0011: busca textual no Postgres (configuração própria, GIN, pesos, keyset com cursor opaco)

- Status: aceita
- Data: 2026-10-22

## Contexto

A busca de licitações é o caminho de leitura mais quente do sistema: texto livre em português (com acento e sem), filtros combináveis e paginação estável sobre cerca de 1 milhão de linhas. A projeção `search.procurement_search_view` já existe, alimentada por eventos (CQRS), e o `objectDescription` do PNCP pode vir vazio ou gigante.

## Decisão

Full-text search nativo do Postgres, sobre a própria projeção.

- Configuração própria `search.portuguese_unaccent` (cópia de `portuguese` com o dicionário `unaccent` antes do `portuguese_stem`): "licitacao" acha "licitação" e "servico" acha "serviços".
- Índice GIN em `search_vector` (`psv_fts_gin`). O vetor tem três pesos: A para o objeto, B para órgão e unidade, C para município e modalidade. O ranking é `ts_rank_cd` (densidade de cobertura), que premia termos próximos, com desempate por `numero_controle_pncp`.
- `q` é interpretado por `websearch_to_tsquery`: aspas, `-` e `or` funcionam e a sintaxe inválida nunca vira erro. O texto vira `tsquery` num primeiro comando trivial e entra na consulta principal como valor: o planejador vê uma constante, estima o casamento pelas estatísticas da coluna, usa o GIN quando compensa e nenhuma linha reprocessa o texto para calcular o rank. Texto só com stopwords ou pontuação gera `tsquery` vazia, e a resposta é 200 com lista vazia, sem consultar a tabela.
- `ts_headline` (`snippet`) é calculado numa consulta externa, só para as linhas da página devolvida, sobre `left(objeto, 100000)`.
- Paginação keyset, nunca `OFFSET`. Com texto: `(rank, id) < (:rank, :id)`, com o rank como `float8` do mesmo `ts_rank_cd` que ordena. Sem texto: `(published_at, id) < (:p, :id)`, atendido por `psv_published_keyset` ou `psv_filters_keyset`. Busca-se `limit + 1` linhas para saber se há próxima página. O limite máximo é 50.
- O cursor é opaco: base64url sem preenchimento de `v1|r|<rank>|<id>` ou `v1|p|<microssegundos desde a época>|<id>`. Microssegundos, e não milissegundos, porque é a precisão da coluna: uma chave mais grossa pularia linhas que dividem o mesmo milissegundo. Cursor de tipo diferente do da consulta (de rank sem texto, ou o contrário), de versão desconhecida ou malformado vira `InvalidCursorException`, que a API responde como 400 `problem+json`.
- Filtros: UF, município (IBGE), modalidade, faixa de valor estimado (linhas sem valor ficam fora quando há filtro de valor), situação, e `open=true`. "Aberta" significa publicada, com encerramento no futuro e abertura já ocorrida ou desconhecida (muitos avisos só informam o encerramento). `open=false` não filtra.
- `unaccent` não é `IMMUTABLE`, então o `tsvector` não pode ser coluna gerada: o projetor o calcula no `INSERT ... ON CONFLICT DO UPDATE`. O objeto é truncado em 100 mil caracteres antes do vetor, por causa dos limites de 1 MB do `tsvector` e de 16.383 posições por lexema; o texto completo fica na coluna.
- A lista devolve só os primeiros 1.000 caracteres do objeto; o texto inteiro está no detalhe.

## Alternativas rejeitadas (e por quê)

- Elasticsearch ou OpenSearch: custo operacional de um cluster a mais para ~1 milhão de linhas, que o Postgres atende com folga; pede um segundo pipeline de indexação e consistência eventual entre a projeção e o índice, justamente o que a projeção CQRS já resolve numa transação só (inbox, versão e checkpoint). Reavaliar se o volume ou a necessidade de relevância (sinônimos, correção de digitação no texto, facetas pesadas) passar do que o FTS entrega.
- Wrapper `IMMUTABLE` em volta de `unaccent` para usar coluna gerada: troca o cálculo no projetor por uma função que mente sobre a imutabilidade (o dicionário pode mudar e o índice não seria reconstruído). Fica registrada como alternativa se o cálculo no projetor virar gargalo.
- `to_tsquery` ou `plainto_tsquery`: o primeiro falha com sintaxe inválida, o segundo não aceita aspas, `-` nem `or`.
- `ts_rank` no lugar de `ts_rank_cd`: ignora a proximidade dos termos, que importa em objetos longos.
- `OFFSET` para paginar: custo cresce com a página e a lista se mexe com inserções; o keyset tem custo constante e é estável.
- Cursor assinado com HMAC: o cursor só carrega chaves de ordenação públicas, então adulterá-lo, no pior caso, pula resultados que o cliente poderia pedir de qualquer jeito. A assinatura traria um segredo a rotacionar sem proteger nada.

## Consequências

- Com texto, o rank não é indexável: a página ordena por `ts_rank_cd` só os `radar.search.rank-candidates` candidatos mais recentes (padrão 2.000), não todas as linhas que casam, e o custo de um termo comum fica limitado (PR 6.3, seção "Perfil e otimização"). O preço é de relevância: o que o ranking vê é o recorte mais recente. A PR 2.7 tinha medido o custo sem limite, que crescia com o número de resultados do termo.
- O texto é compilado em um comando a mais por busca (uma consulta sem tabela, sub-milissegundo).
- O rank do cursor é `float8` do `real` do `ts_rank_cd`: exato na ida e na volta, mas qualquer mudança de versão do Postgres que altere o cálculo pode reordenar uma paginação em andamento (aceito: o cursor expira junto com a sessão do usuário).
- O `snippet` traz texto do PNCP com `<b>` e `</b>`, sem escapar HTML; quem exibe precisa tratá-lo como HTML não confiável.
- Mudar pesos, configuração ou truncamento exige reprojetar (replay) para refletir nas linhas existentes.

## Prova

- `JooqSearchReadModelIT` em `core/src/test/java/br/dev/miguelsanzbr/radar/search/adapter/out/jooq/`: 1.000 linhas semeadas pelo próprio projetor; relevância (objeto acima de órgão), acento e flexão, cada filtro sozinho e combinados, paginação completa com `limit` 7 com e sem texto (sem buracos nem repetição, ordem estritamente decrescente), só stopwords, objeto de 2 milhões de caracteres, e `EXPLAIN` com `psv_fts_gin`, `psv_published_keyset` e `psv_filters_keyset`.
- `CursorTest`, `CursorPropertiesTest` (jqwik: ida e volta e entrada arbitrária), `FiltersTest` e `SearchQueryTest` em `core/src/test/java/br/dev/miguelsanzbr/radar/search/domain/`.
- Benchmark com mais de 1 milhão de linhas: ver "Prova do benchmark (PR 2.7)" abaixo e `docs/benchmarks/search/2026-10-23/`.

## Adendo: busca de órgãos com erro de digitação e estatísticas incrementais (PR 2.3)

### Órgãos

- `GET /organs?q` usa `pg_trgm` sobre `organ_name` da própria `procurement_search_view`, atendido pelo índice `psv_organ_trgm`. A consulta devolve nomes distintos, ordenados por similaridade e depois por nome, com limite de 1 a 20 (padrão 10). Texto em branco ou limite fora da faixa é `IllegalArgumentException` (a API responde 400).
- O operador é `<%` (`word_similarity`), e não `%` (`similarity`). Nomes de órgão são longos ("Prefeitura Municipal de Chapecó") e o texto digitado costuma ser uma ou duas palavras com erro: a similaridade da string inteira de "prefeitra" contra o nome completo fica em torno de 0,2 e nunca passa do limiar, enquanto `word_similarity` compara o texto com o melhor trecho do nome. `<%` também é atendido por `gin_trgm_ops`.
- O limiar é `pg_trgm.word_similarity_threshold`, aplicado com `set_config(..., true)` dentro da transação da consulta (equivale a `SET LOCAL`: a conexão do pool volta limpa). Configurável em `radar.search.organs.similarity-threshold`, padrão 0,3.
- Acento: a comparação é sobre `organ_name` como gravado. `unaccent` não é `IMMUTABLE`, então não há índice sobre `unaccent(lower(organ_name))`, e comparar com ele sem índice varreria o 1 milhão de linhas. O `pg_trgm` já ignora maiúsculas e minúsculas; o acento vira trigramas diferentes: "saude" acha "SAÚDE" só pelos trigramas que os dois compartilham, com similaridade menor que a grafia correta. Quem digita a frase inteira sem acento ainda acha o órgão. Se isso incomodar, a saída é uma coluna `organ_name_unaccented` gravada pelo projetor (mesma razão do `tsvector`), com seu próprio índice.
- Custo: a consulta lê a visão de busca, onde cada órgão aparece uma vez por licitação, então o trabalho cresce com o número de linhas que casam, não com o de órgãos. Numa medição manual num Postgres 18 descartável (1 milhão de linhas sintéticas, 1.500 órgãos distintos, caso ruim em que cada nome casa com 20% da tabela; não versionada), o plano usou `Bitmap Index Scan on psv_organ_trgm` e a consulta ficou na casa de meio segundo, quase todo ele reverificando no heap os candidatos do índice. Esperamos o mesmo comportamento no benchmark da PR 2.7, que é quem registra o número oficial. Se pedir menos, a alternativa é uma tabela `search.organ` (um registro por órgão, mantida pelo projetor), que reduz a consulta a milhares de linhas. A PR 2.7 mediu 2 s e a PR 6.3 implementou essa tabela (seção "Perfil e otimização").

### Estatísticas

- `procurement_stats_view` é mantida pelo projetor, na mesma transação do inbox e da projeção (exatamente uma vez). A chave é `(uf, modality_id, month, value_band)`, com `count` e `estimated_sum`. O mês é `date_trunc('month', published_at AT TIME ZONE 'America/Sao_Paulo')`: uma licitação publicada às 02:30Z de 1º de março ainda é de fevereiro em São Paulo.
- Uma atualização pode mudar a licitação de balde (valor, UF, modalidade ou data de publicação). O projetor toma `pg_advisory_xact_lock(hashtextextended(id, 0))` (um `FOR UPDATE` numa linha que ainda não existe não impediria dois consumidores de verem a licitação como nova e contá-la duas vezes), lê o balde da linha antiga e, se o upsert escreveu a linha (a guarda de versão não a descartou), aplica um delta de -1 ao balde antigo e de +1 ao novo. Os baldes são tocados em ordem de chave, para que duas transações com baldes cruzados não travem uma à outra, e um balde que chega a zero é apagado. Mudança de situação e encerramento não mexem em UF, modalidade, mês nem valor, então não tocam nas estatísticas.
- `reset()` (usado pelo replay) esvazia a tabela de estatísticas junto com as visões, e a reprojeção a reconstrói. Bancos projetados antes desta PR recebem as estatísticas pela migração V15, que só insere quando a tabela está vazia (idempotente) a partir de um `GROUP BY` da visão de busca.
- `uf` é `char(2) NOT NULL` na tabela: UF desconhecida vira `'XX'`. Valor desconhecido soma zero (faixa 0).
- `StatsUseCase.stats(GroupBy)` (UF, MODALITY, MONTH, VALUE_BAND) soma a tabela de estatísticas e nunca lê a visão de busca. A chave volta como texto (UF, id da modalidade, `yyyy-MM`, faixa), ordenada pela coluna de agrupamento.

### Alternativas rejeitadas

- `MATERIALIZED VIEW` com `REFRESH`: custo proporcional à tabela inteira e dado velho entre os refreshes; o incremental custa alguns comandos por evento e fica exato.
- Recalcular o balde com `COUNT(*)` a cada evento: varre a visão de busca por evento.
- Triggers no banco: escondem a regra do código e do teste, e o replay já passa pelo projetor.

### Prova

- `JooqOrganReadModelIT` e `OrganSearchUseCaseTest`: "prefeitra" acha "Prefeitura Municipal de Chapecó", nomes repetidos aparecem uma vez, ordem e limite, limiar mais alto, comportamento com acento e `EXPLAIN` com `psv_organ_trgm`.
- `ProjectionStatsConcurrencyIT`: dois consumidores na mesma licitação (soma das contagens igual ao número de licitações) e licitações trocando de balde em sentidos opostos sem deadlock.
- `JooqProjectionStatsIT`: balde por balde (movimento por valor, UF, modalidade e mês, balde zerado apagado, mês de São Paulo, UF nula, versão antiga, reentrega).
- `ProcurementStatsIT`: mais de 2 mil eventos com atualizações que mudam faixa, UF e mês; para cada `GroupBy`, o resultado é igual a um `GROUP BY` direto na visão de busca, e continua igual quando os mesmos eventos são entregues de novo e depois de `reset()` seguido de `projectAll` em lotes de 500 (o caminho do replay); a mesma classe valida a migração V15.

## Prova do benchmark (PR 2.7)

Medido em 2026-10-23 por `make bench` com 1.200.000 linhas na visão de busca, das quais 1.198.367 sintéticas (inclui dados sintéticos, 1.198.367 linhas), Postgres 18.6 com 2 GiB, uma execução por cenário (`docs/benchmarks/search/2026-10-23/`).

- **Keyset em vez de `OFFSET`.** Páginas 1, 100 e 1.000 de 20 linhas: keyset com p95 de 3 ms nas três; `OFFSET` equivalente com p95 de 4, 7 e 38 ms. Pelo `EXPLAIN`, a página 1.000 custa 0,1 ms por keyset (9 buffers) e 37,7 ms por `OFFSET` com índice (5.755 buffers); sem índice o `OFFSET` leva 3,3 s, com ordenação externa de cerca de 490 MB em disco. O custo do `OFFSET` cresce com a página e o do keyset não.
- **GIN (`psv_fts_gin`).** Termo raro: 857 ms sem índice e 6,6 ms com ele. Termo comum ("contratação", casa com 403 mil linhas): 1.774 ms sem índice e 603 ms com, e o plano continua sendo varredura sequencial paralela, porque o planejador acerta ao não usar o GIN para um termo que casa com um terço da tabela: o gargalo vira ordenar 400 mil linhas por `ts_rank_cd`, exatamente o caso ruim previsto em Consequências.
- **Busca textual S1 (50 usuários).** p50 de 1.005 ms, p95 de 3.402 ms, p99 de 5.857 ms, 142 erros em 3.943 requisições (respostas de erro, consistentes com o `statement_timeout` de 3 s). **Não cumpre** o alvo de p95 de 200 ms e p99 de 500 ms do plano; o valor medido está na ADR-0025. Com 10 usuários: p50 de 108 ms, p95 de 1.888 ms.
- **Busca com UF, modalidade e `open=true` (S2, 50 usuários).** p95 de 44 ms e p99 de 204 ms, sem erros: **cumpre**. O filtro `open=true` usa `psv_open_closing` e o plano cai de 420 ms para 71 ms.
- **Órgãos com erro de digitação (S4).** O adendo da PR 2.3 esperava cerca de meio segundo e `Bitmap Index Scan on psv_organ_trgm`. A medição oficial ficou em 2 s por consulta com varredura sequencial (o texto "camraa municipal" casa com dois terços das linhas, porque `municipal` está em quase todo nome), 544 erros em 720 requisições com 20 usuários e 79 em 103 com 2. A alternativa já registrada, a tabela `search.organ` (uma linha por órgão, mantida pelo projetor), passa a ser a recomendação; foi implementada na PR 6.3 (seção seguinte).
- Planos em `docs/benchmarks/search/2026-10-23/explain/`; reprodução com `make bench`.

## Perfil e otimização (PR 6.3)

Os dois pontos ruins da PR 2.7, tratados com o mesmo benchmark (`docs/benchmarks/search/2026-10-23-pr63/README.md`, mesma massa de 1,2 milhão de linhas, inclui dados sintéticos, 1.198.367 linhas).

### S1: ranking limitado aos candidatos mais recentes

- **Decisão.** A busca com texto tem três níveis. O de dentro filtra por `search_vector @@ tsquery` e pelos filtros, ordena por `published_at DESC, numero_controle_pncp DESC`, toma `radar.search.rank-candidates` linhas (padrão 2.000) e só nelas calcula o `ts_rank_cd`. O do meio aplica o cursor de rank, ordena por rank e toma `limit + 1`. O de fora lê as colunas e o `ts_headline` das linhas da página pela chave primária. O contrato da API não muda.
- **Sem índice novo.** O candidato usa o que já existe: termo raro, o `psv_fts_gin` e uma ordenação pequena; termo comum, o `psv_published_keyset` percorrido em ordem, parando nos 2.000 (3.403 buffers, 2.000 linhas lidas, 7,2 ms contra 602,8 ms). Um GIN composto com `btree_gin` não entrega ordem, então não resolveria nada a mais.
- **Paginação (página 2 em diante).** Os candidatos não dependem do cursor: o filtro do cursor fica no nível do meio, e o nível de dentro tem `LIMIT`, então nenhuma condição desce até ele. Toda página caminha pelo mesmo conjunto de candidatos, e `(rank, id) < (:rank, :id)` continua exato. O fim da lista chega quando os candidatos acabam: uma busca com termo comum devolve no máximo `rank-candidates` resultados em todas as páginas juntas. Para ir além, refine com filtros (UF, modalidade, `open`, valor), que entram no candidato antes do teto: os 2.000 mais recentes **dentro do filtro**. Uma linha nova mais recente que os candidatos pode empurrar a mais antiga para fora entre duas páginas; o cursor não repete nem pula linhas do conjunto que ele viu, mas o conjunto pode mudar (aceito, como já era para as inserções).
- **Custo de relevância, medido** (`scripts/relevance.sh`, 200 termos do S1 de objetos e órgãos reais, na massa de 1,2 milhão de linhas, onde 199 deles casam com mais de 2 mil linhas): com N = 2.000, o top 20 mantém em média 6,4 dos 20 resultados exatos e 82% da soma dos ranks; com N = 500, 2,8 e 68%; com N = 5.000, 7,4 e 88%. O ganho de passar de 2.000 para 5.000 é pequeno, e o custo (linhas lidas no heap por busca) é proporcional a N. Nas 1.633 linhas reais o teto nunca atua. A massa sintética sorteia a data sem relação com o texto: a recência não correlaciona com a relevância, que é a hipótese neutra para medir o custo do teto. Dados reais podem ficar melhores ou piores. O padrão de 2.000 é o do plano; só ele foi medido em latência (p95 de 83 ms no S1), os outros valores só em relevância. É configurável em `radar.search.rank-candidates`.
- **Plano por valor.** As conexões da busca rodam com `plan_cache_mode = force_custom_plan` (`connectionInitSql` do pool). Depois de cinco execuções o driver prepara o comando e o Postgres podia guardar um plano genérico, que para `@@ $1` escolhe o GIN para todo termo, inclusive o comum (a primeira rodada da PR 6.3 teve p95 de 2.230 ms e 69 erros por isso). O custo é replanejar cada comando, e o projetor divide o pool e paga o mesmo: nada disso foi medido nesta PR para o projetor (a medição do planejamento só existe para a consulta de busca, no `EXPLAIN`). O modo é do pool inteiro de propósito; o custo no projetor se mede com `make replay` (`docs/benchmarks/replay/`) antes e depois, e se pesar, o caminho é um pool separado para o projetor.
- **Próximo custo conhecido: termo de frequência média.** Um termo que casa com 1% a 2% da tabela (12 mil a 24 mil linhas) fica entre o caminho do GIN e o do índice de publicação: o plano lê todas as linhas que casam no heap, e a consulta isolada levou de 50 ms a 250 ms em repouso na massa de 1,2 milhão de linhas (`aparelhamento`, `acondicionadas`, `agencia`, `alunos` na medição). Nas rodadas do S1 isso coube no alvo (p99 de 122 ms), mas é o que cresce primeiro com a carga ou com a tabela.
- **Risco do planejador: filtro seletivo com termo pouco correlacionado.** Se o filtro (UF, modalidade) deixa poucas linhas e o termo casa com poucas delas, o planejador pode escolher andar pelo índice de publicação esperando achar os 2.000 candidatos logo, e ler muito mais linhas até completá-los (o `LIMIT` não sabe a seletividade conjunta). O S2 (UF, modalidade e `open=true` com termo comum) cobre o caso bom e fica em 60 ms no `EXPLAIN`; o caso ruim não foi medido, e a estatística estendida (`CREATE STATISTICS`) é o primeiro recurso se aparecer.
- **Resultado:** S1 com 50 usuários de p95 3.402 ms, p99 5.857 ms e 142 erros para p95 83 ms, p99 122 ms e 0 erro em 8.662 requisições. O alvo do plano (p95 200 ms, p99 500 ms) é atendido; ADR-0025.
- **Alternativa rejeitada:** trocar a ordenação por `published_at` quando a contagem estimada passar de um limiar. Muda a ordem que o cliente vê conforme o termo e precisa de uma estimativa que o planejador já faz melhor; o teto mantém a ordem por rank sempre.
- **Teste de regressão** (`SearchRankCandidatesIT`): com 100 mil linhas (um terço com o termo comum), o plano real (`EXPLAIN (ANALYZE, FORMAT JSON)`) de uma busca de termo comum não tem nenhum nó que devolva mais que `rank-candidates` linhas; o mesmo comando sem o teto devolve mais de 25 mil (o controle que prova que o teste pega o defeito). Também cobre: os candidatos são os mais recentes e só eles são ordenados e paginados, termo raro não é cortado, filtros entram antes do teto e o padrão é 2.000.

### S4: tabela `search.organ`

- **Decisão.** Migração V16: `search.organ (organ_name text primary key, procurement_count integer)` com índice `gin_trgm_ops` (`organ_name_trgm`), preenchida da visão de busca quando está vazia. O projetor a mantém na mesma transação da visão: incrementa quando a licitação é nova, move a contagem quando o upsert muda o nome do órgão, apaga o órgão que chega a zero, não toca em atualização do mesmo órgão, versão antiga, reentrega, mudança de situação nem encerramento. Os nomes são tocados em ordem, como os baldes das estatísticas, para que duas transações com órgãos trocados não travem uma à outra. `reset()` (o replay) esvazia a tabela.
- **Consulta.** `JooqOrganReadModel` usa `word_similarity` e `<%` sobre `search.organ`, sem `GROUP BY` (a chave primária já é distinta). Mesma regra de acento do adendo da PR 2.3.
- **Resultado:** S4 de 544 erros em 720 requisições (p95 de 5.025 ms) para 0 erro em 3.545 (p95 de 18 ms); `EXPLAIN` de 2.038,7 ms para 10,9 ms (148 buffers). Com 1.539 órgãos o planejador escolhe varredura sequencial na tabela; o índice entra quando a tabela crescer, e `JooqOrganReadModelIT` prova que ele atende a consulta (com varredura sequencial desligada e 20 mil nomes) e que a consulta não toca na visão.
- **Prova:** `JooqProjectionOrganIT` (contagem por órgão, atualização do mesmo órgão, troca de órgão com remoção do vazio, versão antiga e reentrega, situação e encerramento, lote, `reset()` e a migração).
- **Fica para depois:** o índice `psv_organ_trgm` da visão de busca deixou de servir a leitura e custa escrita; removê-lo é passo de contração (ADR-0010, a versão anterior ainda o usa).
- **Dados que não passam pelo projetor** (`tools/synth`, como as estatísticas) precisam reconstruir a tabela: `scripts/bench.sh` faz isso depois da carga sintética.
- **A migration é idempotente só porque o Flyway a roda uma vez.** O `INSERT` do V16 só preenche se a tabela estiver vazia: se a release anterior projetar entre a migration e o código novo, `search.organ` fica atrás da visão (ver ADR-0010). A reconstrução é `TRUNCATE search.organ` e o `INSERT ... SELECT` do V16 de novo, ou o replay (`make replay`).
