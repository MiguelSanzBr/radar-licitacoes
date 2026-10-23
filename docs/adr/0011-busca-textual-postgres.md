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

- Com texto, a página precisa ordenar todas as linhas que casam (o rank não é indexável): o custo cresce com o número de resultados do termo, não com o tamanho da tabela. Termos muito comuns são o caso ruim; o benchmark da PR 2.7 mede.
- O texto é compilado em um comando a mais por busca (uma consulta sem tabela, sub-milissegundo).
- O rank do cursor é `float8` do `real` do `ts_rank_cd`: exato na ida e na volta, mas qualquer mudança de versão do Postgres que altere o cálculo pode reordenar uma paginação em andamento (aceito: o cursor expira junto com a sessão do usuário).
- O `snippet` traz texto do PNCP com `<b>` e `</b>`, sem escapar HTML; quem exibe precisa tratá-lo como HTML não confiável.
- Mudar pesos, configuração ou truncamento exige reprojetar (replay) para refletir nas linhas existentes.

## Prova

- `JooqSearchReadModelIT` em `core/src/test/java/br/dev/miguelsanzbr/radar/search/adapter/out/jooq/`: 1.000 linhas semeadas pelo próprio projetor; relevância (objeto acima de órgão), acento e flexão, cada filtro sozinho e combinados, paginação completa com `limit` 7 com e sem texto (sem buracos nem repetição, ordem estritamente decrescente), só stopwords, objeto de 2 milhões de caracteres, e `EXPLAIN` com `psv_fts_gin`, `psv_published_keyset` e `psv_filters_keyset`.
- `CursorTest`, `CursorPropertiesTest` (jqwik: ida e volta e entrada arbitrária), `FiltersTest` e `SearchQueryTest` em `core/src/test/java/br/dev/miguelsanzbr/radar/search/domain/`.
- Benchmark com mais de 1 milhão de linhas: PR 2.7 (`docs/benchmarks/search/`).

## Adendo: busca de órgãos com erro de digitação e estatísticas incrementais (PR 2.3)

### Órgãos

- `GET /organs?q` usa `pg_trgm` sobre `organ_name` da própria `procurement_search_view`, atendido pelo índice `psv_organ_trgm`. A consulta devolve nomes distintos, ordenados por similaridade e depois por nome, com limite de 1 a 20 (padrão 10). Texto em branco ou limite fora da faixa é `IllegalArgumentException` (a API responde 400).
- O operador é `<%` (`word_similarity`), e não `%` (`similarity`). Nomes de órgão são longos ("Prefeitura Municipal de Chapecó") e o texto digitado costuma ser uma ou duas palavras com erro: a similaridade da string inteira de "prefeitra" contra o nome completo fica em torno de 0,2 e nunca passa do limiar, enquanto `word_similarity` compara o texto com o melhor trecho do nome. `<%` também é atendido por `gin_trgm_ops`.
- O limiar é `pg_trgm.word_similarity_threshold`, aplicado com `set_config(..., true)` dentro da transação da consulta (equivale a `SET LOCAL`: a conexão do pool volta limpa). Configurável em `radar.search.organs.similarity-threshold`, padrão 0,3.
- Acento: a comparação é sobre `organ_name` como gravado. `unaccent` não é `IMMUTABLE`, então não há índice sobre `unaccent(lower(organ_name))`, e comparar com ele sem índice varreria o 1 milhão de linhas. O `pg_trgm` já ignora maiúsculas e minúsculas; o acento vira trigramas diferentes: "saude" acha "SAÚDE" só pelos trigramas que os dois compartilham, com similaridade menor que a grafia correta. Quem digita a frase inteira sem acento ainda acha o órgão. Se isso incomodar, a saída é uma coluna `organ_name_unaccented` gravada pelo projetor (mesma razão do `tsvector`), com seu próprio índice.
- Custo: a consulta lê a visão de busca, onde cada órgão aparece uma vez por licitação, então o trabalho cresce com o número de linhas que casam, não com o de órgãos. Numa medição manual num Postgres 18 descartável (1 milhão de linhas sintéticas, 1.500 órgãos distintos, caso ruim em que cada nome casa com 20% da tabela; não versionada), o plano usou `Bitmap Index Scan on psv_organ_trgm` e a consulta ficou na casa de meio segundo, quase todo ele reverificando no heap os candidatos do índice. Esperamos o mesmo comportamento no benchmark da PR 2.7, que é quem registra o número oficial. Se pedir menos, a alternativa é uma tabela `search.organ` (um registro por órgão, mantida pelo projetor), que reduz a consulta a milhares de linhas.

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
