# Prova: broker Kafka fora do ar por 60 s

Data: 2026-10-22

Máquina: AMD Ryzen 5 5600GT with Radeon Graphics, 12 threads, 11,5 GiB de RAM (`lscpu | grep 'Model name'`, `/proc/meminfo`). Postgres 18 e Kafka `apache/kafka:4.1.0` (um nó, KRaft) via Testcontainers, um contêiner de cada por JVM.

Comando (a duração da queda vem de `-Dradar.test.broker-outage-seconds`, padrão 60; use 10 para um ciclo rápido):

```
./mvnw -pl core verify -Dit.test='BrokerDownIT' -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
```

## Cenário

`BrokerDownIT` publica 1 evento (aquece o produtor), pausa o contêiner do Kafka (`docker pause`, o mesmo contêiner volta na mesma porta), insere 100 eventos pendentes em 5 agregados e roda `relayOnce()` a cada 500 ms durante a queda. Com `publish-timeout=1s` (só nos testes), o teste exige que durante a queda nenhuma linha seja marcada como publicada, que `last_error` esteja preenchido e que `attempts` continue em 0 (queda do broker não conta tentativa). Depois do `docker unpause`, espera o outbox esvaziar, lê `radar.procurement.v1` do início e exige os 100 `eventId` distintos.

Linha impressa pelo teste:

```
BROKER_DOWN outageSeconds=60 events=100 publishedDuringOutage=0 maxAttempts=0 erroredDuringOutage=1 delivered=104 distinct=100 duplicates=4 drainMs=1020
```

| Medida | Resultado |
|---|---|
| Duração da queda | 60 s |
| Eventos pendentes | 100 |
| Publicados durante a queda | 0 |
| Maior `attempts` ao fim da queda | 0 (a queda não conta tentativa) |
| Linhas com `last_error` ao fim da queda | 1 (só a primeira do lote, o relay encerra o lote ali) |
| Entregues após a volta | 100 distintos (zero perda) |
| Duplicados | 4 (104 mensagens lidas), esperado em entrega ao menos uma vez |
| Tempo para esvaziar após a volta | 1,0 s |
| `aggregateVersion` por agregado na leitura | nunca diminui (o teste confere) |

Execuções anteriores, antes de a queda deixar de contar tentativa, deram 2 e 4 duplicados e 3,8 s e 2,0 s para esvaziar. Os duplicados vêm das mensagens que o produtor ainda tinha na fila quando o relay esgotou o `publish-timeout` e tentou de novo: o produtor idempotente entrega as duas.

## Comportamento observado

- O envio é síncrono com `publish-timeout` (padrão 10 s). Sem resposta do broker, o publicador lança `BrokerUnavailableException` e o relay encerra o lote ali: só a primeira mensagem do lote gasta o tempo, e ela ganha `last_error` mas nenhuma tentativa. Sem isso, um lote de 100 agregados levaria 100 vezes o timeout.
- `max.block.ms` de 10 s impede que `send` fique parado por 60 s à espera de metadados quando o produtor ainda não conhece o tópico.
- `delivery.timeout.ms` de 30 s exige `request.timeout.ms` menor: desde o Kafka 4 o `linger.ms` padrão é 5 ms e `delivery.timeout.ms` precisa cobrir `linger.ms + request.timeout.ms`. Por isso `request.timeout.ms=20000`.
