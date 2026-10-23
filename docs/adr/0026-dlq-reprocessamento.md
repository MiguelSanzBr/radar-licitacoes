# ADR-0026: DLQ, reprocessamento, painel de operação e notas de ordenação

- Status: aceita
- Data: 2026-10-23

## Contexto

Os consumidores de `radar.procurement.v1` (projetor de busca e matcher de alertas) e de `radar.alert.v1` (notificador) já distinguiam mensagem que nunca será lida de falha passageira, mas descartavam a primeira com um log. Perder a mensagem sem rastro não é aceitável: o operador precisa ver o que falhou, corrigir a causa e devolver a mensagem ao fluxo. Além disso, uma queda do banco nunca pode virar perda de mensagem.

## Decisão

**O que vai para a DLQ.**

| Falha | Destino |
|---|---|
| Mensagem que nunca será lida (JSON ilegível, campo obrigatório ausente, `NullPointerException`, `IllegalArgumentException`) | DLQ na primeira falha, sem tentativas |
| Falha que não é do banco e persiste (ex.: envio de e-mail recusado) | DLQ depois das tentativas (3 de 1 s no projetor e no matcher; 1 s, 2 s e 4 s no notificador) |
| Dado que o banco rejeita (`DataIntegrityViolationException`, valor grande demais) | DLQ depois das 3 tentativas: repetir não resolve, e prender a partição não ajuda ninguém |
| Queda do banco: conexão (`DataAccessResourceFailureException`), tempo esgotado ou trava (`TransientDataAccessException`), sem transação (`CannotCreateTransactionException`, `TransactionTimedOutException`) ou, no lado jOOQ, que não tem tradutor do Spring, um `SQLException` cru de tipo transitório, recuperável ou de conexão, ou de SQLState de classe 08, 40, 53 ou 57, ou `55P03`, em qualquer ponto da cadeia de causas | **Nunca.** Tentativa a cada 5 s sem limite, e o consumidor para de avançar até o banco voltar |

A regra mora em `DeadLetters.isOutage`, usada pelos quatro tratadores de erro, e cada classe de tratador tem teste por tipo de exceção.

**O recuperador compartilhado.** `br.dev.miguelsanzbr.radar.ops.DeadLetters` publica o registro em `<tópico>.dlq` (partição escolhida pela chave, então a ordem por chave se mantém dentro da DLQ) com a chave e o valor originais e cabeçalhos de texto: `x-error-class` (classe da causa), `x-error-message` (até 1 024 bytes de UTF-8, cortado sem partir um caractere), `x-original-topic`, `x-original-partition`, `x-original-offset`, `x-consumer-group` (o grupo que desistiu) e `x-failed-at`. Não leva o stack trace: ele é grande e pode carregar dado do evento. O cabeçalho `traceparent` do registro original segue junto. Cada registro enviado incrementa `dlq_messages_total{topic}`; se a publicação na DLQ falhar, nada é contado e o tratador tenta de novo. O notificador reaproveita o mesmo recuperador e mantém a política de 3 tentativas.

**Gravação.** O consumidor `ops-dlq-recorder` lê os dois tópicos `.dlq` desde o início e grava `ops.dlq_message` (tópico, partição, offset, chave, valor, cabeçalhos em `jsonb`, classe e mensagem do erro, `recorded_at`, `reprocessed_at`, `reprocess_result`). A chave única é o registro original e o grupo que falhou, `(original_topic, original_partition, original_offset, consumer_group)`, com `ON CONFLICT DO NOTHING`: uma DLQ publicada duas vezes (ack perdido) ou entregue duas vezes ao gravador não cria segunda linha, e dois grupos que falham o mesmo registro (projetor e matcher) ficam com uma linha cada. Um registro sem cabeçalhos de origem usa a própria posição na DLQ e grupo vazio. Um registro original reprocessado que falhar de novo nas mesmas coordenadas e no mesmo grupo não é gravado duas vezes (`ON CONFLICT DO NOTHING`): a linha existente é a que o operador vê, com o `reprocess_result` da última tentativa. A posição na DLQ (`topic`, `kafka_partition`, `kafka_offset`) fica gravada só para localizar o registro. O próprio gravador segue a regra acima: queda do banco, tentativa sem limite; mensagem que o banco rejeita (um caractere NUL no texto, por exemplo), 3 tentativas e depois um log e o contador `dlq_recorder_skipped_total`, porque ela continua no tópico, que o gravador nunca apaga. Os tópicos `.dlq` usam a retenção padrão do broker (7 dias): depois disso a cópia durável é `ops.dlq_message`, e uma mensagem pulada pelo gravador some do tópico se ninguém agir a tempo, então o contador deve ser vigiado. As colunas se chamam `kafka_partition`, `kafka_offset`, `message_key` e `message_value` porque `offset` é palavra reservada do SQL.

**Reprocessamento.** `POST /ops/dlq/{topic}/reprocess` (papel `operator`) trata uma página de até 500 mensagens pendentes do tópico (ou só a do `?id=`), as nunca tentadas primeiro e depois as que já falharam, cada grupo em ordem de `id` (uma mensagem que falha sempre não bloqueia as outras; para pulá-la, reprocesse as demais com `?id=`): republica no tópico original (o nome sem `.dlq`) com a mesma chave, o mesmo valor e o cabeçalho `x-reprocessed-from=dlq`, espera o aceite do broker e marca `reprocessed_at` e `reprocess_result = 'REPUBLISHED'`. Responde `202` com `count` e `more`; com `more=true` o operador repete a chamada. A primeira falha de envio deixa a mensagem pendente com o motivo em `reprocess_result`, para o reenvio (se gravar o motivo também falhar, o `503` sai igual) e responde `503` (`problem+json` `reprocess-interrupted`) com o `count` feito até ali, porque um broker que recusou uma mensagem recusará as próximas. Se o envio deu certo e só a marcação falhou, a linha não vira `FAILED`: fica pendente, o reenvio seguinte a manda de novo e o inbox absorve; a página pára, porque o banco está mal, e a resposta traz `more=true`. Tópico ou `id` sem mensagem pendente responde `404` (`problem+json`).

**Eventos velhos reprocessados.** O matcher de alertas e o notificador descartam evento mais velho que `radar.alerts.max-event-age`. Um registro com o cabeçalho `x-reprocessed-from=dlq` é velho por definição e foi pedido por um operador, então os dois deixam de aplicar o limite de idade a ele (`DeadLetters.reprocessed`).

**Idempotência.** O reprocessamento não tenta impedir duplicidade (dois operadores ao mesmo tempo, queda entre o envio e a marca): os inboxes (`search.processed_event`, `alerts.processed_event`) guardam o `event_id` e absorvem a repetição. Reprocessar sem corrigir a causa devolve a mensagem à DLQ como um registro novo.

**Painel.** `GET /ops/health-panel` e a página `/ops/painel` reúnem fila de saída, última coleta por modalidade, marcas d'água, atraso por grupo de consumo, DLQ sem reprocessar, ponto de retomada da projeção e flags. Cada seção é lida à parte: uma fonte que falha deixa o componente `DOWN` com o motivo e as outras seções respondendo. O atraso dos consumidores usa duas chamadas ao `Admin` (os offsets confirmados de todos os grupos, depois os fins das partições) sob um prazo único de 5 s: um broker que não responde deixa `consumers` como `DOWN` (atraso desconhecido) em vez de travar o painel. A DLQ aparece por tópico e por grupo de consumo. Os limites são os do alerta `OutboxOldestAgeHigh` (mais antiga acima de 5 s ou qualquer mensagem venenosa deixa a fila `DEGRADED`), 1 000 mensagens de atraso por grupo e qualquer mensagem sem reprocessar na DLQ.

**O que o módulo `ops` lê dos outros contextos.** Nada pelas classes: o Modulith segue sem dependência de `ops` para `ingest`, `search` ou `outbox` (os dois sentidos são verificados por `ModularityTest`). O que lê:

| Dado | Como | Por que direto |
|---|---|---|
| Fila de saída | gauges Micrometer `outbox_pending`, `outbox_oldest_age_seconds` e `outbox_poison_pending`, que o `outbox` já publica | o painel e os alertas leem o mesmo número, e `ops` não consulta tabela do `outbox` |
| Última coleta e marcas d'água | SQL somente leitura em `ingest.ingestion_run` e `ingest.ingestion_cursor` (`JdbcPipelineStats`) | é uma visão de operação entre contextos; uma porta pública em `ingest` só para isto criaria uma dependência `ops` para `ingest` e mais código sem regra de negócio |
| Ponto de retomada da projeção | SQL somente leitura em `search.projection_checkpoint` | idem, com `search` |
| Atraso dos consumidores | `Admin` do Kafka: offsets confirmados dos grupos contra o fim de cada partição, em duas chamadas | o broker é a fonte; `kafka_consumer_lag` ainda não existe como métrica |

Uma tabela não é uma dependência Java, então o Modulith não vê essas consultas; `JdbcPipelineStatsIT` fixa as colunas lidas, e renomear uma delas quebra o teste em vez do painel.

**Notas para o PR 8.4 (rebalance, backpressure, ordenação).**

- Reprocessar recoloca a mensagem no fim da partição, depois de eventos mais novos da mesma chave. Os consumidores precisam tolerar fora de ordem: o projetor descarta versão antiga (`source_version`) e adia o que chega antes do `Discovered`, e o matcher deduplica pela chave primária de `alert_delivery`. O teste de ordenação do 8.4 deve incluir um reprocesso no meio.
- A publicação na DLQ é síncrona dentro do tratador de erros: o consumidor espera o aceite do broker, então um consumidor que falha muito reduz a própria vazão. O 8.4 mede isso junto com o `pause()` e o `resume()` por backpressure. O teste de rebalance deve cobrir uma falha durante a saída de um consumidor (a publicação na DLQ pode ser refeita; o inbox absorve a duplicata).
- O alerta de DLQ (`rate(dlq_messages_total[5m]) > 0`) ainda não existe em `slo.yml`; o painel já fica `DEGRADED`. O 8.4 decide a regra com dados do teste de carga.

## Alternativas rejeitadas (e por quê)

- Tópicos de nova tentativa (`@RetryableTopic`): perdem a ordem por chave e multiplicam tópicos para uma política que três tentativas locais já cobrem.
- Gravar só no banco, sem tópico `.dlq`: se o banco é a causa da falha, o consumidor não consegue gravar a mensagem. O tópico guarda a mensagem sem depender do banco; o banco é a visão do operador.
- Reprocessamento automático: uma mensagem venenosa voltaria em laço. O reprocesso é uma decisão do operador depois de corrigir a causa.
- Guardar o stack trace nos cabeçalhos: tamanho e dado do evento vazando para um tópico de leitura mais ampla.

## Consequências

- Mensagem que não pode ser lida deixa de sumir: aparece no painel, no banco e no contador `dlq_messages_total`.
- Uma queda longa do banco para o projetor e o matcher (consumo parado, atraso crescendo) é o comportamento desejado; o alerta de atraso do consumidor é quem avisa.
- Cada consumidor novo precisa usar `DeadLetters` e `DeadLetters.isOutage` no tratador de erros; um teste por tipo de exceção acompanha cada tratador.

## Prova

- `DeadLettersTest`: cabeçalhos, corte da mensagem, contador, publicação que falha e classificação de queda do banco.
- `SearchKafkaConfigurationTest`, `AlertsKafkaConfigurationTest`, `NotifierKafkaConfigurationTest` e `DlqKafkaConfigurationTest`: queda tentada sem limite, dado rejeitado tentado e depois enviado, mensagem ilegível enviada na hora.
- `DlqFlowIT` (Kafka e Postgres reais): um evento que o projetor rejeita vai para `radar.procurement.v1.dlq` e para `ops.dlq_message` com a classe do erro, `dlq_messages_total` sobe uma vez, o reprocesso o devolve, ele é projetado, a linha fica marcada e um segundo reprocesso devolve 0.
- `JdbcDlqMessagesIT`, `ReprocessDlqUseCaseTest`, `KafkaRepublisherTest` e `DlqControllerTest` (contrato OpenAPI).
- `HealthPanelIT`, `HealthPanelUseCaseTest`, `KafkaConsumerLagsTest` (duas chamadas, prazo único), `KafkaConsumerLagsIT` e `JdbcPipelineStatsIT`; a matriz de autorização (`docs/security/authz-matrix.md`) inclui `/ops/painel` e `/ops/togglz`.
