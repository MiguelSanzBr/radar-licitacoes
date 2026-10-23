# ADR-0024: Apicurio e Avro, com transição dual-format

- Status: aceita
- Data: 2026-10-23

## Contexto

Os eventos (`ProcurementDiscovered`, `ProcurementUpdated`, `ProcurementSituationChanged`, `ProcurementClosed`, `AlertMatched`) saem em JSON com JSON Schema (ADR-0007). A camada 3 acrescenta um registry de schemas e Avro, para que uma mudança que quebra consumidores seja recusada na hora de registrar, e não em produção. É preciso escolher o registry, o formato binário e um caminho de migração que não derrube nenhum consumidor.

## Decisão

**Registry: Apicurio Registry 3.3.3** (Apache 2.0), a mesma imagem do compose (perfil `layer3`), fixada por digest.

**Formato: Avro**, com um registro `.avsc` por tipo de evento em `contracts/src/main/resources/avro/`. Cada schema é autocontido (traz o envelope e o payload tipado), porque o registry guarda um schema por artefato. Campo opcional é união com `null` e padrão `null`. Instantes, dinheiro e UUID continuam strings, como no JSON, e `status`, `frequency` e `uf` são strings e não enums: acrescentar um símbolo a um enum quebra o leitor antigo, acrescentar um valor a uma string não. O teste `AvroSchemasTest` confere que cada `.avsc` declara os mesmos campos, em todos os níveis, e o mesmo conjunto de campos que nunca são nulos que o seu JSON Schema; `envelope.avsc` documenta o cabeçalho comum e não é registrado.

**Formato de fio compatível com a Confluent.** Magic byte `0`, id de 4 bytes (big-endian) e o Avro binário. No Apicurio isso é `apicurio.registry.id-handler=io.apicurio.registry.serde.Default4ByteIdHandler` com `apicurio.registry.use-id=contentId`: o id é o content id do registry, que é o que a API `/apis/ccompat/v7/schemas/ids/{id}` resolve. Os dois valores são o padrão do Apicurio 3, mas ficam explícitos em `WireFormat` para que uma troca de padrão numa atualização não mude o fio sem aviso. `AvroModeIT` lê o id do registro real e o resolve pela API compatível.

**Regra: `BACKWARD` por tópico.** O grupo do artefato é o tópico (`TopicGroupRecordIdStrategy`: grupo = tópico, artefato = nome do registro, ou seja, o tipo do evento). `scripts/schemas-register.sh` (`make schemas-register`) cria os grupos `radar.procurement.v1` e `radar.alert.v1` com a regra, e o serviço do compose sobe com `APICURIO_RULES_GLOBAL_COMPATIBILITY=BACKWARD` como rede de segurança para um grupo criado sem o script. `apicurio.registry.dereference-schema=true` faz cada evento virar um único artefato; sem isso o serde registra cada registro aninhado como artefato próprio.

**Registro dos artefatos: automático, no primeiro uso.** O produtor registra a versão (`auto-register`, `FIND_OR_CREATE_VERSION`) na primeira publicação de cada tipo. Um schema que quebra a regra é recusado pelo registry e a publicação falha; o script não registra schema, só prepara os grupos. Escolhi um só escritor do conteúdo do schema (o código que o produz) para não ter duas grafias do mesmo schema no registry.

**Onde a conversão mora.** Um par de serdes do Kafka, `EnvelopeSerializer` e `EnvelopeDeserializer`, configurados por propriedade (`spring.kafka.producer.value-serializer` e `spring.kafka.consumer.value-deserializer`). Dentro da aplicação o valor continua sendo o texto JSON do envelope: o publicador, os quatro consumidores (projetor de busca, matcher e notificador de alertas, gravador de DLQ), o recuperador de DLQ e o reprocessamento não mudaram. O serializador converte o JSON no registro Avro do tipo do evento; o desserializador decide o formato pelo cabeçalho `content-type` e, sem ele, pelo primeiro byte (`0` é Avro, um JSON nunca começa assim) e devolve o JSON. O inbox, a guarda de `source_version`, a DLQ e o `traceparent` ficam como estavam, porque os consumidores ainda recebem o mesmo texto.

**Falhas, produtor.** Tudo o que o registry ou o Avro recusam (tipo de evento sem schema, campo ausente ou de tipo errado, violação da regra de compatibilidade, resposta 4xx, erro do codificador Avro) é `SerializationException`: conta tentativa da linha do outbox e, esgotadas as tentativas, vira poison como qualquer linha ruim. Só o que pode passar sozinho (falha de rede, registry que não responde dentro do prazo, resposta 408, 429 ou 5xx) é um `TimeoutException` do Kafka, que o publicador trata como queda do broker: o lote para, nenhuma tentativa é gasta e as linhas esperam. A classificação lê a cadeia de causas (`WireFormat.transientFailure`).

**Falhas, consumidor.** O desserializador nunca lança no laço de leitura. Avro que nunca vai decodificar (id de schema que o registry não conhece, bytes truncados ou corrompidos com um id conhecido: o `EOFException` e o `AvroRuntimeException` do decodificador são permanentes, mesmo o primeiro sendo um `IOException`) vira `avro-undecodable:<bytes em Base64>`, que nenhum parser JSON aceita: o listener o rejeita como malformado e o registro vai para a DLQ com os bytes preservados no texto. Avro que não decodificou por causa do registry (falha de rede como conexão recusada ou reiniciada, sem resposta no prazo, 408, 429 ou 5xx) vira `avro-registry-unavailable:<bytes em Base64>`, e cada listener (projetor, matcher, notificador, gravador de DLQ) o rejeita com `RegistryUnavailableException`, que `DeadLetters.isOutage` reconhece: o contêiner repete o registro a cada 5 s, sem limite, e o decodifica de novo a cada tentativa. Uma queda do registry atrasa o consumo, nunca manda registro para a DLQ.

**Prazo das chamadas ao registry.** O cliente HTTP do JDK que o Apicurio usa não tem prazo de leitura e a configuração dele não está exposta por propriedade. Cada chamada roda numa thread virtual e quem chama espera no máximo `radar.messaging.registry-timeout-ms` (10 s por padrão, mínimo 1). As duas repetições do cliente (`retry-count=2`, `retry-backoff-ms=200`) rodam dentro desse prazo, não depois dele: o pior caso de uma chamada com o registry mudo é o prazo, não indefinido. Passado o prazo, vale a classificação acima: produtor com linhas esperando, consumidor repetindo.

**Janela do consumidor.** Uma leitura do Kafka traz até 500 registros e cada chamada falha ao registry custaria o prazo inteiro, o que estouraria `max.poll.interval.ms` e tiraria o consumidor do grupo. Depois de uma falha transitória o desserializador marca todo registro Avro na hora, sem chamar o registry, por `radar.messaging.registry-backoff-ms` (5 s por padrão, 0 desliga); a leitura custa então um prazo mais o tempo de marcar o resto. O registro cujo schema já está em cache também espera essa janela, e a repetição do contêiner (5 s) o decodifica depois dela. `spring.kafka.consumer.max-poll-records` pode ser reduzido se a leitura ainda demorar.

**Dead letters.** Os tópicos `.dlq` são sempre texto: o recuperador copia os cabeçalhos do registro de origem, e o serializador remove o `content-type: application/avro` herdado. O desserializador exige o byte mágico além do cabeçalho, então um texto JSON com o cabeçalho herdado continua texto. A DLQ guarda o JSON que o consumidor leu, não os bytes originais do Avro; o reprocessamento republica esse JSON no tópico de origem, e o serializador o converte de volta em Avro.

**Formato de configuração.** `radar.messaging.format` aceita `json` e `avro` em qualquer caixa e qualquer outro valor falha a partida (`MessagingProperties`).

**O que o consumidor vê.** O consumidor decodifica com o schema do escritor (o do registry, pelo id do registro) e entrega o JSON dele: um campo que o escritor deixa de gravar some do JSON, e o `EnvelopeParser` e os registros do alerts exigem os campos do contrato, então remover campo obrigatório quebra os consumidores mesmo quando o registry aceita (ver o `BACKWARD` abaixo). Um campo que o JSON do outbox traz e o `.avsc` não declara é descartado na conversão: o `.avsc` é o contrato do formato Avro e o teste de paridade o mantém igual ao JSON Schema.

**Produção.** Em produção o registry deve governar os schemas e a aplicação não deveria criar versão. Com `spring.kafka.producer.properties.apicurio.registry.auto-register=false` e `apicurio.registry.find-latest=true` o produtor usa a última versão registrada em vez de registrar a do código; ela precisa ser a do `.avsc` do código, porque o registro é montado a partir dele. Nesta entrega o padrão é o auto-register, que serve ao compose e aos testes.

**Transição.** (1) Os consumidores aceitam JSON e Avro desde este PR, com o produtor ainda em JSON. (2) Com o registry no ar e `make schemas-register` rodado, vira-se o produtor com `radar.messaging.format=avro` (`RADAR_MESSAGING_FORMAT` no compose). (3) Quando não houver mais JSON a ler (retenção do tópico de procurement é infinita, então só depois de um replay ou de reescrever o tópico), remove-se o ramo JSON. O padrão fica `json` aqui porque o Avro é a camada 3, opcional: o registry é mais um componente no caminho crítico, e `make up` e todos os testes existentes seguem sem ele.

**O modo CDC fica em JSON.** No modo `cdc` o valor do registro é montado pelo banco (`infra/cdc/enable-cdc.sql`) e o Debezium o envia como texto. O consumidor lê esse JSON como sempre; Avro com CDC exigiria um conversor no conector e está fora do escopo.

## Apicurio ou Confluent Schema Registry

- Licença: o Apicurio é Apache 2.0; o Confluent Schema Registry usa a Confluent Community License, que proíbe oferecê-lo como serviço concorrente. Para um projeto aberto, a licença decide.
- Compose: o Apicurio em memória cabe em 512 MB, uma imagem, sem dependência de Kafka para guardar os schemas (o Confluent grava no tópico `_schemas`).
- Fio: o Apicurio 3 escreve o formato da Confluent com o `Default4ByteIdHandler` e expõe a API `/apis/ccompat/v7`, então um consumidor com as bibliotecas da Confluent lê os registros. O inverso (o serde do Apicurio ler registro da Confluent) vale pelo mesmo formato. O custo: o id é o content id do Apicurio, não o id sequencial do Confluent.

## Avro ou Protobuf

- Evolução: as duas linguagens evoluem com campos opcionais. No Avro a regra é dos dois lados, leitor e escritor, e o registry a verifica sobre o schema inteiro (inclusive remoção e mudança de tipo); no Protobuf a compatibilidade vive nos números dos campos e depende da disciplina de não reutilizá-los.
- Ferramentas: o Avro lê um registro sem código gerado (`GenericRecord`), o que permitiu converter de e para o JSON do envelope sem uma classe por evento. O Protobuf pede geração de código ou descritores dinâmicos.
- Tamanho: Avro não grava nomes de campo; medido neste PR, o registro Avro tem de 51% a 58% do JSON (docs/benchmarks/schema/evolution.md). O Protobuf ficaria na mesma faixa.

## Alternativas rejeitadas (e por quê)

- Confluent Schema Registry: licença e peso no compose, acima.
- Protobuf: sem ganho de evolução e com geração de código que o Avro dispensa aqui.
- Um conversor por consumidor (cada um recebendo bytes e escolhendo o formato): quatro cópias da mesma decisão e uma DLQ em bytes, que o gravador e o reprocessamento teriam de aprender. O serde único mantém tudo em texto.
- Registrar os artefatos pelo script: duas grafias do mesmo schema no registry (o arquivo e o que o serde produz), uma versão a mais a cada deploy.
- Virar o padrão para Avro: acopla o caminho crítico ao registry antes de haver um consumidor externo.

## Consequências

- Mais dependências no classpath (`apicurio-registry-avro-serde-kafka` com o cliente HTTP do JDK; o Vert.x é excluído porque traria o Netty 4.1 contra o 4.2 do Boot).
- O `BACKWARD` aceita remover um campo (o leitor novo ignora o que não conhece) e recusa acrescentar campo sem padrão ou mudar tipo. A remoção de campo obrigatório só é recusada com `FULL`; a prova, com as respostas reais do registry, está em `docs/benchmarks/schema/evolution.md`. A regra do projeto (campo novo é opcional; remoção ou mudança de tipo exige `schemaVersion` novo, ADR-0007) continua valendo por cima do registry.
- A serialização em Avro custa mais CPU que copiar o texto (medida indicativa no relatório); aceito, o volume é pequeno.
- O registry fora do ar com o formato `avro` para a publicação (as linhas esperam no outbox) e atrasa o consumo de Avro que o consumidor ainda não decodificou (o registro é repetido, nunca vai para a DLQ); JSON e os schemas já em cache seguem passando. O registry guarda os schemas em memória no compose: reiniciar o contêiner apaga os artefatos, e o `auto-register` os recria no primeiro uso, mas os ids mudam; registro Avro já gravado no Kafka fica ilegível até o schema voltar com o mesmo id. Em produção o registry precisa de armazenamento persistente.

## Prova

- `AvroSchemasTest`: os `.avsc` espelham os JSON Schemas.
- `EnvelopeSerializerTest`, `EnvelopeDeserializerTest`: fio compatível com a Confluent, ida e volta dos cinco eventos, falhas.
- Testes de cada consumidor com o registro em Avro (`EnvelopeParserTest`, `ProcurementEventsListenerTest`, `ProcurementEventsForAlertsListenerTest`, `AlertMatchedListenerTest`, `DlqRecorderListenerTest`).
- `SchemaEvolutionIT` (`make avro-test`): evolução contra um Apicurio de verdade.
- `AvroModeIT` (`make avro-test`): outbox, relay, projetor e matcher em modo avro.
- `AvroDlqIT` (`make avro-test`): DLQ em texto, gravação como JSON e reprocessamento em modo avro; registry pausado com o registro sendo repetido e depois projetado, sem DLQ.
- `docs/benchmarks/schema/evolution.md`: respostas do registry, tamanho e tempo.
