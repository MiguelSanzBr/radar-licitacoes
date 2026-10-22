# Idempotência do projetor de busca

Prova da PR 1.9: o mesmo lote de eventos entregue várias vezes deixa o schema `search` byte a byte idêntico.

## Cenário

- Teste: `ProjectionIdempotencyIT` (`core/src/test/java/br/dev/miguelsanzbr/radar/search/adapter/in/kafka/`).
- Infraestrutura: Postgres 18 e Kafka 4.1 do Testcontainers (um de cada por JVM), tópico `radar.procurement.idempotency-it.v1` (próprio do teste, 12 partições, para não misturar com eventos de outros testes), grupo de consumo `search-projector-idempotency-it` (o `search-projector` da produção, renomeado pela propriedade `radar.search.projector.group`), projetor e listener reais, confirmação manual de offset.
- Lote fixo de 200 envelopes: 50 licitações, cada uma com `ProcurementDiscovered` (versão 1), `ProcurementUpdated` (versão 2), `ProcurementSituationChanged` (versão 2, para suspensa ou anulada) e `ProcurementClosed` (versão 3). Um em cada cinco licitações não tem valor estimado. O `eventId` de cada envelope é fixo, então as três passadas reenviam os mesmos 200 eventos.
- Antes do lote o teste envia 12 envelopes de tipo desconhecido (um por partição) até o broker aceitar todos: um tópico recém-criado pode responder `NOT_LEADER_OR_FOLLOWER` por um tempo numa máquina carregada. O projetor confirma e ignora essas sondas, que não entram em `processed_event`.
- Passadas: 3. Depois de cada uma o teste espera o grupo confirmar todos os offsets (lag zero e offsets finais do tópico de pelo menos o total publicado) e tira um dump.
- Dump: as tabelas `procurement_search_view`, `procurement_detail_view` e `processed_event`, cada uma ordenada pela chave primária, sem as colunas `projected_at`, `updated_at` e `processed_at` (relógio do banco). O `search_vector`, o `detail` em `jsonb` e o `etag` entram no dump. `projection_checkpoint` fica fora do hash, porque o offset do último evento depende de como as 12 partições se intercalam em cada execução; o teste só confere que existe uma linha.

## Resultado

Data: 2026-10-22.

| passada | eventos publicados (acumulado) | SHA-256 do dump |
|---|---|---|
| 1 | 200 | `b6609d4b0fe951fa666a62f7efce7bf5a34c203faeadad1452247eb4738c3034` |
| 2 | 400 | `b6609d4b0fe951fa666a62f7efce7bf5a34c203faeadad1452247eb4738c3034` |
| 3 | 600 | `b6609d4b0fe951fa666a62f7efce7bf5a34c203faeadad1452247eb4738c3034` |

O hash é determinístico: os `eventId` e os dados do lote são fixos, e nada do que entra no dump vem do relógio ou da ordem de chegada entre partições. Ao final: `processed_event` com 200 linhas (uma por evento, apesar de 600 entregas), 50 linhas em `procurement_search_view`, e os 50 detalhes com `closedAt` e histórico de duas transições.

## Como reproduzir

```
./mvnw -pl core -am verify -Dit.test=ProjectionIdempotencyIT
cat core/target/idempotency-hashes.txt
```

O teste grava os três hashes em `core/target/idempotency-hashes.txt` e falha se algum difere do primeiro.

## Outras provas do mesmo projetor

`JooqProjectionStoreIT` cobre: versão 2 depois da versão 3 (o conteúdo da 3 permanece), versão igual (não sobrescreve), situação e fechamento antes do `Discovered` (nada gravado, nem o `processed_event`; o contador `projection_deferred_total` registra o caso, e quem o evita é a ordem por agregado do relay, não o replay), vetor de busca sem acento e com pesos A, B e C, e objeto de 2 milhões de caracteres (truncado em 100 000 só no `tsvector`).
