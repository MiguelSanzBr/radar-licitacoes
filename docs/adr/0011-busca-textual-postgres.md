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
