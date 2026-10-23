# ADR-0015: matching de alertas por busca reversa em SQL, uma varredura por evento

- Status: aceita
- Data: 2026-10-22

## Contexto

Cada licitação descoberta ou alterada precisa achar as buscas salvas que casam com ela, para avisar o assinante. É o caminho inverso da busca: em vez de uma consulta contra muitas licitações, uma licitação contra muitas consultas. O volume de eventos é baixo (cerca de 1 600 licitações por dia na modalidade 6, ver a sonda) e o número de buscas salvas é limitado: no máximo 20 por assinante, só de assinantes verificados.

## Decisão

Uma consulta SQL por evento sobre as buscas salvas ativas, no mesmo banco e na mesma transação do restante do processamento (`MatchProcurementUseCase`, adaptador `JdbcReverseSearch`):

- O texto da licitação (objeto, órgão, unidade e município) vira um `tsvector` uma vez, com a configuração `search.portuguese_unaccent` do lado da busca, e cada `query_text` é testado com `websearch_to_tsquery` e `@@`. A mesma configuração nos dois lados mantém acento, caixa e stemming iguais.
- Filtros de `filters` (jsonb): `uf`, `ibge`, `modality`, `minValue`, `maxValue` e `open`. Um dado que a licitação não tem (sem UF, sem valor, sem fim de proposta) nunca satisfaz o filtro correspondente.
- `query_text` vazio é busca só por filtros: casa tudo o que passa nos filtros. Uma busca feita só de stopwords vira `tsquery` vazia e nunca casa.
- Só entram assinantes verificados e não apagados, e buscas ativas.
- A consulta roda com o usuário principal (dono dos schemas), que enxerga a configuração de texto do schema `search`; não há migração de permissão. Se o matcher passar a usar outro papel, ele precisa de `USAGE` no schema `search` e na configuração, sem permissão em tabelas.
- A deduplicação é a chave primária de `alerts.alert_delivery (saved_search_id, procurement_id)`: o `INSERT ... ON CONFLICT DO NOTHING` só emite `AlertMatched` quando insere uma linha. Um `ProcurementUpdated` do mesmo edital ou o mesmo evento entregue de novo não gera alerta novo. O inbox `alerts.processed_event` (consumidor `alerts-matcher`, `event_id`) evita refazer a consulta em reentrega.
- `ProcurementUpdated` só dispara o matcher se `changedFields` tocar `objectDescription`, `estimatedValue`, `proposalWindow`, `unit`, `organ` ou `modality`. Mudança de situação, `ProcurementSituationChanged` e `ProcurementClosed` são reconhecidos sem trabalho.
- O grupo `alerts-matcher` começa no fim do tópico (`auto.offset.reset=latest`), e um evento cujo `occurredAt` do envelope é mais velho que `radar.alerts.max-event-age` (padrão `P1D`, relógio injetado) é reconhecido sem trabalho, antes de gravar no inbox. Assim um backlog lido depois de uma queda, a perda dos offsets do grupo ou um replay não transformam licitações antigas em alertas novos.

## Custo medido

10 000 buscas ativas (1 000 assinantes, uma ou duas palavras de um vocabulário de cerca de 100, um terço com filtro de UF; 78 casam com o evento), em `MatchBenchmarkIT`, p50 de 50 execuções, no Ryzen 5 5600GT com Postgres 18 em contêiner (sete execuções do teste deram de 21,7 a 31,6 ms só na consulta e de 49,8 a 93,1 ms no evento inteiro, com outros processos no mesmo computador):

| Medida | p50 | Máximo |
|---|---|---|
| Só a consulta de busca reversa | 24,0 ms | 34,2 ms |
| Evento inteiro (inbox, consulta, 78 linhas de entrega, 78 `AlertMatched` no outbox, commit) | 61,9 ms | 153,1 ms |

O custo é linear no número de buscas ativas: cerca de 2,4 µs por busca na consulta. Detalhes e a reprodução estão em `docs/benchmarks/alerts/dedup.md`.

Com os volumes do projeto isso sobra: 1 600 eventos por dia a 24,0 ms cada ocupam menos de um minuto de um consumidor por dia. O caso caro é um backfill: 0,6 milhão de eventos de 12 meses, a 24,0 ms por evento, levam algumas horas de um consumidor com 10 000 buscas (e mais com mais buscas). A idade máxima não ajuda aqui, porque um backfill ao vivo publica eventos novos (`occurredAt` de agora) sobre licitações antigas. Durante um backfill histórico o matcher deve ficar desligado, com `radar.alerts.matcher.enabled=false`; ao religar, o grupo continua do fim do tópico, e o que foi publicado nesse intervalo e tem menos de `max-event-age` precisa ser reenviado (replay) se alertas dele forem desejados.

## Quando trocar

Trocar de estratégia quando a consulta passar de 250 ms no p50 (por volta de 80 000 buscas ativas, extrapolando a medida acima, que não foi confirmada nessa escala), ou quando a taxa sustentada de eventos vezes o p50 ocupar mais da metade de um consumidor (por exemplo, 10 eventos por segundo a 50 ms). Antes disso, subir a concorrência do consumidor (o tópico tem 12 partições, a chave é o número de controle) escala a vazão sem mudar o desenho.

## Alternativas

- Coluna `tsquery` pré-compilada em `saved_search`: tira o `websearch_to_tsquery` de dentro da varredura (o parse acontece uma vez, na criação da busca). Continua uma varredura O(N) e precisa recalcular a coluna se a configuração de texto mudar; o ganho não foi medido aqui. É o primeiro passo se a varredura virar o gargalo, por ser uma migração e uma mudança de escrita, sem infraestrutura nova.
- Índice invertido de termos: tabela termo para buscas, alimentada na criação da busca com os lexemas positivos da consulta; por evento, os lexemas do texto da licitação buscam candidatas e só elas passam pelo `@@` e pelos filtros. Reduz a varredura ao número de candidatas, mas exige tratar consultas sem termo positivo (só negação ou só filtros) e manter o índice em toda criação, edição e exclusão. Entra se a pré-compilação não bastar.
- Percolator no OpenSearch: é busca reversa nativa e escala bem, mas traz um serviço a mais para operar e um segundo analisador de texto que diverge do Postgres no stemming; o desenho atual mantém uma única definição de "casa". Só se justifica se o OpenSearch já existir para a busca principal e as buscas salvas passarem de centenas de milhares.

## Consequências

- O matcher e a gravação do alerta acontecem na mesma transação do inbox e do outbox: ou tudo vai, ou nada (testado com falha no outbox).
- O `AlertMatched` sai com chave `subscriberId` (tópico `radar.alert.v1`, ordem por assinante) e carrega o que o e-mail mostra, para o notificador (PR 3.3) não consultar nada além do envelope.
- Uma busca nova não gera alerta de licitações antigas ao ser criada: o matcher só olha eventos novos. Uma licitação que passa a casar por causa de uma alteração gera alerta uma única vez por busca.
- `modality` é filtro de busca e entra nos campos que disparam o matcher, então uma mudança só de modalidade pode fazer uma busca passar a casar (e alerta uma única vez por busca).
- Tratamento de erro como o do projetor de busca: mensagem malformada (`MalformedEventException`, JSON ilegível, campo obrigatório ausente) é descartada sem nova tentativa; falha de banco ou de transação (`DataAccessException`, `TransactionException` em qualquer ponto da cadeia de causas) é tentada sem limite a cada 5 s, então uma queda nunca descarta evento; qualquer outra falha é tentada 3 vezes, 1 s entre elas, e depois descartada com log, até a DLQ chegar no PR 4.3.
- `query_text` só com espaços vale como busca só por filtros, igual ao texto vazio.

## Prova

`docs/benchmarks/alerts/dedup.md`: cenários de deduplicação (um alerta, três reentregas, atualização do mesmo edital, só mudança de situação, filtros, assinante apagado ou não verificado) e o tempo do matching contra 10 000 buscas. Testes: `MatchProcurementUseCaseIT`, `JdbcReverseSearchIT`, `ProcurementEventsForAlertsIT` e `MatchBenchmarkIT`.
