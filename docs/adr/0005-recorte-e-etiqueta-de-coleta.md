# ADR-0005: recorte e etiqueta de coleta

- Status: aceita
- Data: 2026-10-23

## Contexto

O plano deixou aberto quanto do PNCP coletar (modalidades e janela de meses), como o benchmark mistura dado real e sintético e como a coleta se identifica. A sonda de coleta mediu a API pública em 22/10/2026 e o relatório está em `docs/benchmarks/probe/2026-10-22.md`. Ela rodou três vezes (2.646 requisições, de 17:02 a 19:44), parou na primeira resposta 429 e **não será retomada**: a janela de 24 h, a rampa de taxa (S3) e a repetição do S7 não foram feitas. O que isso deixa sem medida está na seção "O que não foi medido e o efeito disso" do relatório. Os números abaixo são da execução 2 do relatório (a única com a varredura de volume completa, 419 de 420 células); onde a ferramenta soma execuções, o relatório explica e corrige com leitura do JSONL.

Volume medido de 22/09 a 21/10/2026 (S7), em linhas de `contratacoes/publicacao` por dia de publicação:

| Recorte | 30 dias | Por ano (média de 30 dias vezes 365) |
|---|---|---|
| Modalidade 6 (Pregão Eletrônico) | 33.345 | 405.698 |
| Todas as 14 modalidades | 124.710 | 1.517.305 |

Outras leituras do mesmo relatório:

- Dia útil da modalidade 6: de 1.418 a 1.703 linhas (média de 1.582). Fim de semana e feriado: de 0 a 23. Pelo menor e pelo maior dia útil, a modalidade 6 fica entre 372 mil e 446 mil linhas por ano. A estimativa de 0,6 milhão do plano estava cerca de um terço acima do medido.
- As maiores modalidades por ano, pela mesma média: 8 (dispensa) com 745.513, 6 com 405.698 e 9 com 262.459.
- Cada item de `publicacao` pesa 1.928,9 bytes na resposta (média de 95.427 bytes por página de 50). Para a modalidade 6, 12 meses são cerca de 0,78 GB de JSON bruto antes de compressão e índices (cálculo sobre o relatório, não medição).
- Custo de coletar: com páginas de 50 e pelo menos uma requisição por dia, a modalidade 6 pede cerca de 8.300 requisições por ano de dados e todas as modalidades cerca de 34.300. A 0,2 req/s (ADR-0017) são 11,5 h e 47,7 h de relógio, sem nova tentativa (cálculo sobre o relatório).

## Decisão

1. **Recorte do MVP: modalidade 6, 12 meses de publicação.** São cerca de 0,41 milhão de linhas reais (faixa de 0,37 a 0,45 milhão). A coleta inicial cabe em duas noites da janela de 0h às 6h a 0,2 req/s. O recorte continua em `radar.ingest.modalities=6`.
2. **Ampliar para todas as modalidades fica fora do MVP.** O 1,5 milhão de linhas por ano das 14 modalidades passa de 1 milhão, mas a regra do plano pede também que a coleta seja viável, e isso não ficou demonstrado: o único teste de taxa foi o 429 aos 0,5 req/s, a rampa S3 não rodou, e coletar 12 meses de todas as modalidades levaria cerca de oito noites, a uma taxa (0,2 req/s) que ainda não foi sustentada por noites seguidas. Quando o backfill da modalidade 6 tiver rodado sem 429 e com a latência por hora medida pela própria aplicação, acrescentar modalidades é mudar a propriedade e registrar a decisão numa nova ADR. A modalidade 8, a maior, é a candidata natural.
3. **Benchmark: dado real mais dado sintético rotulado.** A massa do benchmark de busca é a coleta real da modalidade 6 (cerca de 0,41 milhão, não os 0,6 milhão do plano) completada por linhas sintéticas até 1,2 milhão no total, o que deixa perto de 0,79 milhão sintético. A regra "só dado real" do plano não vale agora, pelo item 2. A rotulagem da seção "Dados sintéticos", abaixo, não muda: todo número de benchmark traz "inclui dados sintéticos (N linhas)". O particionamento (ADR-0010) continua dimensionado para o teto de 0,6 milhão do plano, que cobre a faixa medida com folga e deixa espaço para sazonalidade, que só foi observada em 30 dias.
4. **Etiqueta de coleta.** Toda requisição ao PNCP, da aplicação e da sonda, carrega `User-Agent` com nome do projeto, versão, endereço do repositório e e-mail de contato (o valor exato da aplicação está no ADR-0017). A taxa é de 0,2 req/s sustentados (ADR-0017). O backfill roda na janela de 0h às 6h, um dia por vez, páginas de 50. A aplicação não paraleliza além do limitador. Nada de coleta paralela por outras ferramentas do projeto. A sonda está encerrada, e qualquer nova medição na API real precisa de decisão nova.
5. **`robots.txt` e termos (S9).** O PNCP não publica `robots.txt`: duas conexões foram fechadas sem resposta e a terceira recebeu HTTP 404 com o HTML do portal (59.879 bytes). Isso não proíbe nada, mas também não autoriza. Os termos de uso do portal **não foram lidos**: o plano registra que o manual da API de consultas respondia 401 para busca automatizada e a leitura manual não consta no relatório. Segue-se com coleta identificada, de baixa taxa e na janela noturna, com um item em aberto.

## Itens em aberto

- Ler à mão os termos de uso do PNCP e o manual da API de consultas. Se houver proibição explícita de coleta automatizada, o projeto passa para coleta sob demanda do usuário e isso vira uma ADR que substitui esta.
- A sazonalidade do volume (fim de ano, recesso) não foi medida. O teto de 0,6 milhão cobre uma variação de até 45% sobre os 0,41 milhão. Acima disso, rever o particionamento.
- O limite real de taxa do PNCP é desconhecido (ver ADR-0017). Quem opera o backfill precisa acompanhar o contador de respostas 429.

## Alternativas rejeitadas (e por quê)

- Todas as modalidades já no MVP: viabilidade de coleta não demonstrada (item 2) e custo de cerca de oito noites de backfill contra duas.
- Só dado real no benchmark: o volume real do recorte (0,41 milhão) fica abaixo do 1,2 milhão que o benchmark de busca precisa, e a regra do plano para dispensar o dado sintético não foi atendida.
- Janela de coleta menor que 12 meses: o benchmark e a busca reversa perderiam histórico sem economia relevante de coleta (cerca de 8.300 requisições por ano de dados).
- Retomar a sonda para completar S3 e as 24 h: já houve um 429 e o risco de bloqueio do IP do operador pesa mais que a precisão ganha.

## Consequências

- O benchmark publica o volume real e o sintético separados, com o rótulo de sempre.
- O relatório da sonda fica como evidência versionada, com o que não mediu declarado. Os padrões que dependiam das 24 h (latência na madrugada, taxa segura por rampa) viram métricas da própria aplicação.
- Quem ampliar o recorte precisa de nova decisão, não só de mudar a propriedade.
- Os termos de uso seguem como risco aceito e visível até a leitura manual.

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

## Prova

`docs/benchmarks/probe/2026-10-22.md` (relatório da sonda, com S7 e a seção do que não foi medido). A regra de rotulagem do dado sintético é provada pelos testes de `tools/synth`, descritos na seção anterior.
