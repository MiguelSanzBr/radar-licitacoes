# Evolução do schema dos eventos: Avro no Apicurio

Data: 2026-10-23

Commit: `test(outbox): indicative size and serialization time of json against avro` (PR 8.1; o relatório vale para o HEAD do PR).

Máquina: AMD Ryzen 5 5600GT, 12 threads, 11,5 GiB de RAM, Linux 6.12, Docker 29.9.2, JDK 25. Registry: `apicurio/apicurio-registry:3.3.3` (a imagem do compose, em memória, 512 MB), um contêiner iniciado pelo teste. A máquina não estava ociosa (outros builds em paralelo), o que pesa só nos tempos da última seção.

Comandos:

```
make avro-test
./mvnw -Pbench test -pl core -am -Dtest=SchemaBenchTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Regra

Cada tópico é um grupo do registry (`radar.procurement.v1`, `radar.alert.v1`) com a regra `COMPATIBILITY=BACKWARD`; cada tipo de evento é um artefato do grupo (por exemplo `ProcurementClosed`). `BACKWARD` quer dizer que a versão nova lê o que a anterior escreveu: o consumidor atualiza primeiro, o produtor depois.

`scripts/schemas-register.sh` prepara os grupos. Respostas do registry a essa preparação, no mesmo contêiner:

| Chamada | Resposta |
|---|---|
| `POST /groups` `{"groupId":"radar.procurement.v1"}` | 200, grupo criado |
| `POST /groups/radar.procurement.v1/rules` `{"ruleType":"COMPATIBILITY","config":"BACKWARD"}` | 204 |
| a mesma chamada de novo | 409 `RuleAlreadyExistsException`; o script então faz `PUT .../rules/COMPATIBILITY`, que responde 200 |
| `POST /groups` de um grupo que existe | 409 `GroupAlreadyExistsException`; o script segue |

## Passos da evolução

`SchemaEvolutionIT` usa o schema real de `ProcurementClosed` num grupo `BACKWARD` próprio de cada teste. As respostas abaixo são do registry (corpos abreviados onde dizem `...`).

1. Registrar a v1.

   `POST /groups/{grupo}/artifacts` com `artifactType=AVRO`: **200**, `"version":"1"`, `"globalId":1`, `"contentId":1`.

2. Acrescentar um campo opcional com padrão (`note`, união `["null","string"]`, padrão `null`) em `payload`.

   `POST /groups/{grupo}/artifacts/ProcurementClosed/versions`: **200**, `"version":"2"`, `"globalId":7`, `"contentId":7`. Registrou.

3. Acrescentar um campo obrigatório sem padrão (`agency`, `string`).

   **400**, `RuleViolationException`:

   ```
   Incompatible artifact: ProcurementClosed [AVRO], num of incompatible diffs: {1}, list of diff types: [agency at /fields/8/type/fields/3]
   ```

   Recusado, porque a versão nova não teria o que ler nos registros antigos.

4. Mudar o tipo de `closingAt` de `string` para `long`.

   **400**, `RuleViolationException`: `reader type: LONG not compatible with writer type: STRING at /fields/8/type/fields/2/type`.

5. Remover o campo obrigatório `closingAt`.

   Com `BACKWARD`: **200**, `"version":"2"`. É aceito: o leitor novo ignora o campo que não conhece do escritor antigo. O brief e o plano pediam que o registry recusasse a remoção de campo obrigatório; isso só acontece com `FULL`, em que a versão antiga também tem de ler o que a nova escreve:

   Com `FULL`: **400**, `RuleViolationException`:

   ```
   Incompatible artifact: ProcurementClosed [AVRO], num of incompatible diffs: {1}, list of diff types: [closingAt at /fields/8/type/fields/2]
   ```

   O projeto fica em `BACKWARD` (a regra do plano e da ADR-0024). Remover campo, na prática, continua pedindo `schemaVersion` novo por regra da ADR-0007, porque um consumidor antigo que ainda lê o campo removido quebraria.

O `AvroModeIT` confirma o resto: com os grupos criados pelo script, o relay publica `ProcurementDiscovered` e `AlertMatched` em Avro, o registry passa a ter o artefato de cada um no grupo do seu tópico e o id do registro (bytes 1 a 4) é resolvido por `GET /apis/ccompat/v7/schemas/ids/{id}`, a API da Confluent.

## Tamanho do registro

Os eventos de exemplo de `EventSamples` (o snapshot de um edital com todos os campos preenchidos; o evento real do relay tem cerca de 1,6 KB, `docs/benchmarks/cdc/polling-vs-debezium.md`), em bytes do valor do registro, sem cabeçalhos:

| Evento | JSON | Avro | Avro / JSON |
|---|---|---|---|
| `ProcurementDiscovered` | 1 153 | 585 | 0,51 |
| `ProcurementUpdated` | 1 205 | 617 | 0,51 |
| `ProcurementSituationChanged` | 531 | 284 | 0,53 |
| `ProcurementClosed` | 432 | 252 | 0,58 |
| `AlertMatched` | 717 | 397 | 0,55 |

Um edital típico (`ProcurementUpdated`) ocupa 617 bytes em Avro contra 1 205 em JSON. O Avro não grava nomes de campo, e o texto em português com acentos pesa igual nos dois (UTF-8). Os 5 bytes do formato de fio (magic byte e id) já estão contados.

## Tempo de serialização (indicativo)

`SchemaBenchTest` serializa o mesmo `ProcurementUpdated` 10 000 vezes depois de 2 000 de aquecimento, numa só JVM, sem JMH. O registry fica em memória (sem ida e volta de rede) e o tempo cobre só o serializador de valor: em JSON ele copia o texto para bytes; em Avro ele lê o JSON, monta o registro, codifica e escreve o cabeçalho. A construção do envelope em JSON, comum aos dois, não entra. Duas execuções:

| Execução | JSON, serializar | Avro, serializar | Avro, decodificar de volta para JSON |
|---|---|---|---|
| 1 | 1,60 µs | 24,56 µs | 16,88 µs |
| 2 | 1,39 µs | 24,11 µs | 20,50 µs |

Leia como ordem de grandeza: o Avro custa algumas dezenas de microssegundos por evento, contra o custo de uma publicação síncrona com `acks=all` (milissegundos). A diferença não aparece no gargalo do relay.

## O que este relatório não mostra

- Latência fim a fim com o registry no caminho (a primeira publicação de cada tipo faz uma chamada HTTP de registro; as seguintes usam o cache do cliente).
- Comportamento do registry com armazenamento persistente.
