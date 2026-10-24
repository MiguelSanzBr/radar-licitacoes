# Prova: anomalias de isolamento no Postgres

Data: 2026-10-23. Código das medições: `core/src/test/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/jpa/LostUpdateIT.java`, `core/src/test/java/br/dev/miguelsanzbr/radar/db/NonRepeatableReadIT.java` e `core/src/test/java/br/dev/miguelsanzbr/radar/db/SerializableDeliveryIT.java`, no commit `6b1a370`.

## Ambiente

- Hardware: AMD Ryzen 5 5600GT (12 threads), 11 GiB de RAM, NVMe, Linux 6.12, Docker 29.9. O host rodava outras compilações em paralelo (load average de 7 a 11): os contadores são exatos, os tempos têm ruído.
- Postgres 18 (`postgres:18` via Testcontainers, o contêiner compartilhado da JVM de teste), schema pelo Flyway (V1 a V14), nível de isolamento padrão (READ COMMITTED) onde o teste não pede outro.
- Cada teste usa duas threads, cada uma com a sua conexão e a sua transação, sincronizadas por `CyclicBarrier` e `CountDownLatch`, para que a intercalação seja a mesma em toda execução.
- Comandos (um teste por vez):

```
./mvnw -pl core -am verify -Dit.test=LostUpdateIT -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl core -am verify -Dit.test=NonRepeatableReadIT -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl core -am verify -Dit.test=SerializableDeliveryIT -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
```

Os números saem no log de cada teste, em linhas que começam com `isolation`. Nenhum dos três passa de 30 s no host carregado, então rodam no `verify` normal, sem a tag `bench`.

## 1. Lost update em `ingest.procurement`

**Montagem.** Uma licitação gravada pelo repositório; 200 corridas, cada uma em uma licitação nova. Duas transações em READ COMMITTED leem o mesmo `revision` (r). A primeira escreve r+1 e confirma; só depois a segunda escreve r+1 e confirma. Eram dois incrementos, deveria sobrar r+2.

**Sem `@Version`** (um `UPDATE ... SET revision = ?` que ignora a coluna `version`, o que o JPA faria sem a trava): o `UPDATE` da segunda transação não encontra nada de errado, porque o READ COMMITTED só reavalia o `WHERE` depois da trava de linha, não o valor lido antes. A escrita da primeira se perde.

**Com `@Version`** (o `JpaProcurementRepository` real): as duas threads carregam o agregado, a primeira grava e a segunda tenta gravar a versão antiga. O repositório compara a versão carregada com a da linha e lança `ObjectOptimisticLockingFailureException` (`OptimisticLockingFailureException` é a classe-mãe que a porta documenta). O código de aplicação ainda não tem laço de retry (o chamador da porta é quem repete, como a documentação da porta diz); o teste faz o que o chamador faria: recarregar o agregado, reaplicar o snapshot e gravar de novo.

| Variante | Resultado em 200 corridas | Tempo médio por corrida |
|---|---|---|
| `UPDATE` ignorando a versão | 200 de 200 perderam um incremento (`revision` ficou em r+1) | 3,1 a 3,3 ms |
| `@Version`, sem retry | 200 de 200 segundas gravações recusadas | n/a |
| `@Version`, com um retry | 200 de 200 recusadas na primeira tentativa, 400 tentativas no total (2 por corrida), `revision` final r+2 e histórico completo (descoberta e as duas mudanças de situação) em todas | 19,5 a 21,4 ms |

Tempos de duas execuções seguidas, medindo só a corrida (sem a carga inicial). O custo da variante com `@Version` inclui as leituras pelo repositório, as duas gravações com histórico e o retry; a variante ingênua é um `SELECT` e um `UPDATE` por conexão.

**Correção adotada.** Trava otimista com `@Version` no agregado de `ingest`, como já estava (ADR-0010). O retry é do chamador: o ciclo de coleta seguinte relê a licitação e reaplica o snapshot, e a recusa só acontece quando dois fluxos tocam a mesma licitação ao mesmo tempo.

## 2. Leitura não repetível em uma leitura de estatísticas

**Montagem.** Dez linhas de `search.procurement_search_view` para uma UF de teste, 100,00 cada. A transação A lê `count(*)` e `sum(estimated_value)` da UF; a transação B (outra thread, outra conexão) insere uma linha de 50,00, muda uma linha de 100,00 para 200,00 e confirma; A lê de novo, ainda aberta.

| Nível de A | Primeira leitura | Segunda leitura |
|---|---|---|
| READ COMMITTED | 10 linhas, soma 1000,00 | 11 linhas, soma 1150,00 (viu o commit de B) |
| REPEATABLE READ | 10 linhas, soma 1000,00 | 10 linhas, soma 1000,00 (mesmo instantâneo) |

**Correção.** Quando um cálculo lê o mesmo agregado mais de uma vez (ou compara duas leituras) e precisa de uma resposta coerente, a transação abre em REPEATABLE READ: o Postgres entrega o instantâneo do primeiro comando até o fim, sem travar quem escreve. O projetor de estatísticas chega na PR 2.3 (em andamento); este teste usa a view de busca como cobaia, e a escolha do nível do projetor fica registrada na ADR da 2.3.

## 3. SERIALIZABLE com retry em `alerts.alert_delivery`

**Montagem.** A entrega de um alerta deve existir uma vez por par (busca salva, licitação). Duas formas de garantir isso, medidas em 200 corridas de dois escritores concorrentes para o mesmo par:

- **Tabela real** (`alerts.alert_delivery`, chave primária composta): `INSERT ... ON CONFLICT DO NOTHING` em READ COMMITTED, como o `JdbcAlertDeliveries`. Os dois começam juntos; o segundo espera o commit do primeiro na trava do índice único e depois não insere nada.
- **Alternativa** (`iso_test.alert_delivery_copy`, `CREATE TABLE ... (LIKE alerts.alert_delivery INCLUDING DEFAULTS)`, ou seja, sem a chave primária nem restrição de unicidade): `INSERT ... SELECT ... WHERE NOT EXISTS (...)` em SERIALIZABLE. Na primeira tentativa os dois escritores só confirmam depois de os dois terem executado o `INSERT` (ambos leram "sem linha"), o que garante a sobreposição. Falha com SQLSTATE `40001` desfaz e recomeça; a segunda tentativa já vê a linha do vencedor e não insere.

**Resultado (uma execução, load average 11).**

| Estratégia | Tentativas (400 mínimo) | Falhas `40001` | Tempo por corrida (média, p50, p95, máx.) | Linhas por par |
|---|---|---|---|---|
| unique + `ON CONFLICT DO NOTHING` | 400 | 0 | 2,58 / 2,80 / 3,50 / 11,50 ms | 1 em 200 de 200 |
| SERIALIZABLE, retry com pausa aleatória | 642 | 242 (200 no commit, 42 no statement) | 6,14 / 5,82 / 15,30 / 28,70 ms | 1 em 200 de 200 |
| SERIALIZABLE, retry imediato | 1205 | 805 | 2,12 / 2,08 / 2,61 / 5,63 ms | nunca 2; 0 a 29 corridas esgotaram as 20 tentativas nas execuções feitas |

Leitura:

- Em todas as variantes nunca houve duas linhas para o mesmo par; o invariante é do banco, não da sorte da intercalação.
- Com SERIALIZABLE a falha é certa quando há sobreposição: em todas as 200 corridas um escritor foi recusado no `COMMIT`. Quando os dois escritores repetem ao mesmo tempo, colidem de novo (39 corridas das 200 na execução acima precisaram de mais de uma repetição, e em outras execuções foram de 1 a 37). Por isso o retry tem pausa aleatória crescente (`0 a 4 * 2^tentativa` ms): sem ela, os dois repetem em marcha sincronizada e o limite de tentativas se esgota (de 0 a 29 corridas em 200 nas execuções sem pausa, mais quanto maior a carga do host).
- A tabela real com `ON CONFLICT` custa 2 tentativas por corrida, nenhuma falha e nenhuma lógica de retry no código. A unique continua sendo a escolha: o mesmo resultado, mais barato e sem laço de repetição no chamador.
- Variação entre execuções: com pausa, as tentativas ficaram entre 601 e 713 e a média entre 6,1 e 31,6 ms (a segunda faixa é do host sob load de 20 ou mais); as da tabela real, entre 2,5 e 21,8 ms.

**Quando SERIALIZABLE valeria a pena.** Quando a regra de unicidade não puder ser expressa como restrição (um limite por assinante sobre linhas que mudam, por exemplo): ali o `ON CONFLICT` não ajuda e o retry em `40001` é o preço da garantia.
