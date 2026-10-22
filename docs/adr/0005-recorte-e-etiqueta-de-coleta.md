# ADR-0005: recorte e etiqueta de coleta

- Status: proposta
- Data: 2026-10-22

Esta ADR é aberta pelo gerador sintético e guarda por enquanto só a seção abaixo. O recorte da coleta (modalidades e janela de meses) e o restante da etiqueta serão completados com as medições da sonda.

## Dados sintéticos

O benchmark de busca precisa de mais linhas do que o PNCP entrega no recorte coletado. `tools/synth` completa a massa com linhas sintéticas, sob uma regra de rotulagem que vale para todo o repositório: **dado sintético nunca aparece misturado ao real sem o rótulo**.

Regras de rotulagem:

- Toda linha sintética grava `source = 'synthetic'` em `search.procurement_search_view` (a coluna tem `'pncp'` como padrão, então o dado coletado nunca precisa de marcação).
- O rótulo vale nos dois caminhos: o modo `copy` grava `source = 'synthetic'` direto, e o projetor de busca, ao consumir eventos do modo `events` (replay), grava `source = 'synthetic'` para todo id que começa com `SYN-` (e `'pncp'` para os demais), na inserção e na atualização.
- Todo id sintético começa com `SYN-` e segue `SYN-<14 dígitos>-1-<6 dígitos>/<ano>`. Um número de controle real nunca começa com `SYN-`, então os dois conjuntos não colidem.
- Toda tabela, gráfico ou frase do README, de relatório de benchmark ou de documento que inclua essas linhas traz o rótulo "inclui dados sintéticos (N linhas)". Número de benchmark sem esse rótulo é número de dado real, e só pode ser dito assim se `source <> 'synthetic'` for verdade para toda a massa medida.
- O gerador não passa pelo domínio de coleta: `ProcurementId` continua aceitando só o número de controle do PNCP. As linhas vão direto para o read model (modo `copy`) ou para um arquivo de eventos separado (modo `events`).

Como o dado é gerado (`java -jar synth.jar --mode=copy|events --count=N --seed=S`, ou `make synth`):

- Corpus: o fixture real gravado em `infra/compose/seed` e, se informados em `--corpus`, outros arquivos JSON lines do PNCP. Dele saem trigramas de palavras com backoff para `objetoCompra`, nome do órgão e nome da unidade, a distribuição de município, UF e código IBGE, o histograma de valor estimado em escala logarítmica, modalidade, situação, modo de disputa, amparo legal, SRP e o tamanho da janela de propostas. Nada aprendido é gravado no repositório além do que o fixture já é.
- Determinismo: o mesmo corpus, `--seed` e `--count` dão a mesma saída (gerador `L64X128MixRandom`). Em modo `events` o arquivo `.jsonl.gz` é idêntico byte a byte.
- Datas: as publicações se espalham em ordem crescente pelos 365 dias anteriores à última data de publicação do corpus (e não pelo relógio da execução, para o resultado não depender do dia).
- Texto: só usa palavras que existem no corpus, com média de tamanho próxima à do corpus. Órgão, unidade e município vêm de um conjunto fixo de 2.000 órgãos sintéticos, que se repetem como na vida real; os campos são sorteados de forma independente entre si.
- Modo `copy`: `COPY` em lotes de 50 mil linhas para uma tabela temporária e um `INSERT ... SELECT` que calcula o `search_vector` com a mesma expressão do projetor (A = objeto, B = órgão e unidade, C = município e modalidade, configuração `search.portuguese_unaccent`). Reexecutar a mesma seed não duplica nem altera linhas. O `procurement_stats_view` não é preenchido pelo gerador: continua sendo produto do projetor e do replay.
- Modo `events`: envelopes `ProcurementDiscovered` no mesmo JSON que o relay publica, para o replay carregar no Kafka. O padrão de id dos esquemas em `contracts` aceita o prefixo opcional `SYN-` (`^(SYN-)?\d{14}-\d+-\d{6}/\d{4}$`), então o arquivo valida contra os esquemas como está.

Limites: o texto sintético não é texto real de licitação e serve para dar volume e distribuição de termos ao índice de busca, não para avaliar relevância. A senha do banco só entra por variável de ambiente (`--password-env`), nunca pelos argumentos.

Prova: `tools/synth/src/test` (determinismo, ids que nunca colidem com os do fixture, texto só com palavras do corpus, esquemas dos eventos, carga de 10 mil linhas com `search_vector` e rótulo).
