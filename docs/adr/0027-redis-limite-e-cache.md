# ADR-0027: Redis: limite de requisições por balde de fichas e cache do detalhe

- Status: aceita
- Data: 2026-10-22

## Contexto

A API pública de leitura é aberta e precisa de um teto de requisições por cliente, mais alto para quem tem chave de API (ADR-0019). O contador precisa ser compartilhado entre instâncias da aplicação e atômico. O Redis do compose (`redis:8.8-alpine`, sem persistência, 64 MB) já existe para isso. Esta ADR cobre o limite e, na segunda parte, o que o mesmo Redis cacheia e o que não cacheia.

## Decisão

**Balde de fichas por chave de API ou por endereço**, num script Lua (`core/src/main/resources/identity/token-bucket.lua`) executado com uma só chamada por requisição, então refilar, tirar uma ficha e responder são atômicos, mesmo com várias instâncias. Limites por minuto, de configuração: `free` 60, `pro` 600, anônimo (por endereço) 30. A capacidade é o limite e o balde refila a capacidade inteira em 60 s, continuamente (60 por minuto são uma ficha por segundo).

**Aritmética exata.** O nível é contado em 1/janela de ficha: um pedido custa a janela (60 000) e cada milissegundo decorrido devolve a capacidade. Tudo é inteiro, nada arredonda e o balde não empresta nem perde ficha. `TokenBucketModel` (fontes de teste) é a mesma conta em Java, serve de oráculo nas propriedades jqwik (nunca passa da capacidade mais o que refilou em qualquer trecho; rajada num instante deixa passar exatamente a capacidade) e é comparado ao script real em `RedisRateLimiterParityIT`, inclusive com relógios que andam para trás.

**Relógio.** O tempo vem do `TIME` do Redis, dentro do script: todas as instâncias leem o mesmo relógio e não há desvio entre elas. O script aceita um quarto argumento opcional com o "agora" em milissegundos, que só os testes passam (para mover o tempo à mão e comparar o script com o modelo em Java). Um tempo atrás do guardado (relógio de teste, ou failover para uma réplica levemente atrasada) não refila nem recua o balde.

**Memória do Redis.** Toda chave de balde tem TTL (a ficha cheia já equivale a chave ausente, então expira em `tempo até encher + 1 s`). O Redis roda com `--maxmemory-policy volatile-ttl`: com a memória cheia ele descarta as chaves que expiram primeiro, em vez de recusar escritas (o padrão `noeviction` faria todo script falhar com OOM e o limite abriria para todos). Isto vale para o compose e para qualquer Redis externo (Helm). Quem acrescentar chaves ao mesmo Redis (o cache do detalhe, abaixo) precisa dar TTL a todas.

**Cabeçalhos.** Toda resposta medida traz `RateLimit-Limit`, `RateLimit-Remaining` e `RateLimit-Reset` (segundos até o balde ficar cheio). O 429 traz também `Retry-After` (segundos até voltar uma ficha, no mínimo 1) e o problema `rate-limited`.

**Onde.** Filtro de servlet depois da cadeia de segurança, só nos `GET` públicos (`/api/v1/procurements/**`, `/api/v1/stats`, `/api/v1/organs`). Rotas com papel não passam por ele. O balde é `key:<id da chave>` ou `ip:<cliente>`. O cliente é o endereço remoto; só com `radar.http.trust-forwarded=true` vale a última entrada do `X-Forwarded-For`, a que o proxy acrescentou, nunca as da esquerda, que o chamador escreve. Premissa: exatamente um proxy confiável na frente (um salto); com proxies encadeados a entrada certa muda e a regra precisa de um número de saltos. A entrada tem de ser um IP literal (sem consulta a DNS); caso contrário vale o endereço remoto. IPv4 entra como está, IPv4 mapeado em IPv6 (`::ffff:a.b.c.d`) vira o IPv4, e IPv6 é medido pela rede /64 (um assinante recebe um /64 inteiro; por endereço, rodar o endereço contornaria o limite).

**Chave inválida.** O cabeçalho `X-Api-Key` precisa ter a forma emitida (`rk_` + 8 hex + `_` + 43 caracteres de base64url); outro formato é recusado sem consultar o banco. Toda chave malformada, desconhecida ou revogada gasta uma ficha do balde `ip:` do endereço, como uma leitura anônima: o palpite de chaves também é limitado, e com o balde vazio a resposta é 429 em vez de 401. A resposta 401 leva `WWW-Authenticate: ApiKey`.

**Redis fora: o limite abre (fail-open).** Se o script falha (conexão, prazo de 250 ms ou comando rejeitado), a requisição passa sem medição, sem cabeçalhos `RateLimit-*`, o contador `rate_limit_fail_open_total` sobe e o log registra um WARN por minuto. Motivos: a API é leitura pública de dados públicos, então indisponibilidade custa mais que uma janela sem teto; o estado é descartável (balde ausente é cheio); o Redis não deve ser ponto único de falha da leitura. O que mudaria a decisão para fail-closed: rotas que gravam ou custam dinheiro por chamada, dados que não sejam públicos, ou um abuso sustentado durante a queda (o runbook indica desligar o limite na borda ou reduzir exposição). Fail-closed seria devolver 503 com `Retry-After` no mesmo ponto.

## Alternativas rejeitadas (e por quê)

- Resilience4j `RateLimiter` em memória: o contador não é compartilhado entre instâncias e some a cada reinício.
- Janela fixa com `INCR` e `EXPIRE`: deixa passar o dobro na virada da janela e o `INCR`+`EXPIRE` em dois passos não é atômico sem script.
- Ficha em ponto flutuante: arredonda e o resultado difere entre Lua e Java; a aritmética inteira evita isso.
- Bucket4j com Redis: dependência a mais para uma conta de vinte linhas que precisamos entender e testar de ponta a ponta.

## Consequências

- Redis recusando conexão (caído): o cliente (`LettuceClientOptionsBuilderCustomizer` com `DisconnectedBehavior.REJECT_COMMANDS`) rejeita os comandos assim que percebe a desconexão, então a leitura passa sem esperar (`RateLimitRedisDownIT` mede o p95 abaixo de 50 ms com o contêiner parado). Só nas primeiras leituras, até a desconexão ser percebida, e com o Redis pendurado (pausado, sem responder), cada leitura espera até o prazo de 250 ms; a fila de pedidos é limitada (1024) para um Redis pendurado não empilhar requisições. O mesmo limite também restringe os comandos em andamento na conexão compartilhada: acima de 1024 leituras simultâneas medindo ao mesmo tempo, as excedentes passam sem medição (fail-open) em vez de esperar. Com threads virtuais (`spring.threads.virtual.enabled=true`) a espera não prende uma thread de plataforma do Tomcat, mas continua somando latência por leitura. Um disjuntor (Resilience4j já está no classpath) removeria a espera do caso pendurado; fica para quando houver medição que justifique.
- Chave inválida em rota pública gasta ficha do endereço, mas ainda custa uma consulta ao banco quando tem a forma certa (o formato barra o resto); a segurança vem dos 256 bits da chave.
- O corpo da resposta de `POST /api/v1/api-keys` traz a chave em claro: o log de requisição e resposta em nível DEBUG (por exemplo `logging.level.org.springframework.web=DEBUG`) não pode ficar ligado em produção. `ApiKey.Issued.toString()` oculta o segredo para que ele não vaze por log de objeto.
- O contador é do cliente que apresenta a chave: quem vaza a chave gasta a cota do dono, que a revoga por prefixo.

## Cache do detalhe

### Decisão

**Cache-aside do detalhe armazenado.** Guarda-se o que o `DetailReadModel` devolve: o JSON do detalhe (com o histórico) e o `etag` gravado pelo projetor. Não se guarda a fase da proposta nem o ETag HTTP: o controlador continua calculando os dois a cada requisição, porque a fase depende do relógio (`sha256(etag armazenado + ":" + fase)`). Assim o validador muda no instante em que a janela abre ou fecha mesmo com o detalhe em cache (`DetailCacheIT` avança o relógio e confere).

**Onde.** `RedisDetailCache`, em `search.adapter.out.redis`, decora a porta `DetailReadModel` (o caso de uso e o controlador não mudam). Chave `search:detail:v1:<id>`, valor em JSON, TTL de 1 h (`radar.search.detail-cache.ttl`), todo `SET` com expiração (a política do Redis é `volatile-ttl`). `radar.search.detail-cache.enabled=false` tira o decorador: a leitura vai direto ao Postgres e nada toca o Redis. O `v1` da chave é a versão do formato: qualquer mudança no `ProcurementDetail`, inclusive campo acrescentado, exige subir o número. Numa implantação gradual instâncias velhas e novas dividem o mesmo Redis, e um valor escrito no outro formato seria lido como um detalhe diferente sem erro (campo ausente vira nulo ou zero); com a chave nova cada versão só lê o que escreveu e as chaves antigas expiram sozinhas. Um valor que não se lê como o formato atual conta como erro e é trocado pela linha do banco. O TTL precisa ser positivo: um valor zero ou negativo impede a aplicação de subir.

**Inexistente não é guardado.** Um id que o banco não conhece não vai para o Redis. Cache negativo curto protegeria o Postgres contra varredura de ids, mas deixaria quem escolhe as chaves encher o orçamento de memória; o limite de requisições (primeira parte) é a defesa contra a varredura, e o `DEL` do evento `Discovered` já cobriria a janela entre a primeira consulta e a projeção.

**Invalidação depois do commit.** O `JooqProjectionStore` registra, dentro da transação do projetor, uma `TransactionSynchronization.afterCommit` que apaga a chave do agregado de todo evento aplicado (`Discovered`, `Updated`, `SituationChanged`, `Closed`; no `Discovered` a chave normalmente não existe, e o `DEL` é só uniformidade). Nunca antes do commit: apagar antes deixaria uma leitura concorrente recolocar no cache a linha velha. Num lote (`projectAll`, replay) a transação é a do lote: os ids dos eventos aplicados (sem repetição) são reunidos e uma só sincronização depois do commit do lote os apaga com um `DEL` de várias chaves, em vez de um por evento. Transação desfeita não apaga nada (`DetailCacheIT` prova os dois lados: a chave segue lá durante a transação e some só depois do commit; com `setRollbackOnly` ela fica). Falha do Redis em qualquer operação do cache (qualquer `RuntimeException`, inclusive ao fechar o cursor do `SCAN`) nunca lança (o commit já aconteceu e o Kafka reentregaria o evento à toa): conta em `search_detail_cache_errors_total`.

**Corrida que sobra.** Uma leitura que perdeu o cache antes do commit e leu a linha velha do Postgres pode gravar essa linha no Redis depois do `DEL` do commit, e o detalhe antigo fica até o TTL. A janela é a de uma consulta em voo durante o commit (milissegundos), e o TTL de 1 h é o teto da defasagem. Não se fecha com versão por chave (exigiria uma leitura a mais do Postgres a cada acerto) nem com trava; a mesma defasagem máxima vale quando um `DEL` falha por queda do Redis. Para um detalhe de edital, uma hora no pior caso, com ETag coerente com o conteúdo servido, é aceitável. O mesmo vale se a leitura do Postgres vier de uma réplica atrasada (hoje a leitura usa o primário): a réplica ainda sem o commit devolveria a linha velha, que seria gravada depois do `DEL`; o TTL continua sendo o teto, e a defasagem da réplica soma-se à janela.

**Replay.** `reset()` do store limpa o espaço `search:detail:v1:*` depois do commit do `TRUNCATE`, com `SCAN` (lote de 500) e `UNLINK` em lotes, nunca `KEYS`. Durante o replay os ids de cada lote continuam sendo apagados depois do commit do lote, num único `DEL` de várias chaves por lote (necessário porque as leituras continuam entrando e podem recolocar linhas parciais). Não se trocou o prefixo de versão para limpar: o `SCAN` é simples e não deixa chaves órfãs ocupando memória.

**Redis fora: fail-open.** Qualquer falha numa operação do cache (conexão, prazo de 250 ms, comando rejeitado, cliente fechado) é contada em `search_detail_cache_errors_total` e logada como WARN no máximo uma vez por minuto; a leitura segue no Postgres. Depois de uma falha, leituras e gravações do cache deixam de tentar o Redis por 10 s (mesmo relógio do WARN), para não pagar o prazo a cada requisição com o Redis pendurado; passada a janela, a próxima leitura tenta de novo. As invalidações nunca são puladas: são o que mantém o cache correto, e com um `DEL` só por commit o custo é uma chamada. Uma invalidação perdida deixa o detalhe antigo até o TTL, se o Redis voltar com os dados. Mesmo motivo da primeira parte: o cache é otimização, nunca dependência. O cliente é o mesmo do limite, configurado por `RedisClientConfiguration` (pacote raiz, sem depender de nenhuma flag de funcionalidade): Lettuce com `REJECT_COMMANDS` e prazos de 250 ms. Com o Redis recusando conexão a leitura não espera, ligado ou não o limite. Com o Redis pendurado, a primeira leitura espera o prazo e as seguintes seguem direto ao Postgres durante a janela. `DetailCacheRedisDownIT` (limite desligado) para o contêiner e confere que o detalhe sai do Postgres, que a projeção não quebra, que o contador sobe e que as chamadas ficam abaixo de 50 ms (p95).

**Métricas.** `search_detail_cache_requests_total{result="hit"|"miss"}` (taxa de acerto = hit / (hit + miss); leitura com Redis fora não entra em nenhum dos dois) e `search_detail_cache_errors_total`. Painel no dashboard `busca-api` ("Cache do detalhe: taxa de acerto e erros do Redis").

### Por que não cachear a lista de busca

A chave teria de conter `q`, filtros, cursor e limite: a cardinalidade é praticamente a de cada requisição, o reaproveitamento é baixo e cada entrada ocupa mais que um detalhe (uma página de resumos). Pior, qualquer projeção pode mudar a posição e o conteúdo de muitas páginas; invalidar por evento exigiria saber quais buscas o evento afeta, que é a própria busca. O que a lista tem de reaproveitável já está no navegador e nos proxies: ETag fraco e `If-None-Match` (304), sem estado no servidor. O detalhe é o oposto: uma chave por edital, leitura repetida pelo mesmo id, invalidação exata por agregado.

### Orçamento de memória

O Redis do compose tem 64 MB e `volatile-ttl`. Medido em `docs/benchmarks/redis/hit-ratio.md`: um detalhe com histórico pequeno tem cerca de 800 bytes de JSON e cerca de 1 KB de memória do Redis por chave (`used_memory` dividido pelas chaves), então 64 MB comportam da ordem de 60 mil detalhes, sem contar os baldes do limite (uma chave pequena por cliente, também com TTL). As 1 796 chaves do teste ocupam cerca de 1,8 MB. Se a memória encher, `volatile-ttl` descarta primeiro as chaves que expiram antes (as mais antigas do cache e os baldes quase cheios), e o efeito é uma taxa de acerto menor, nunca erro de escrita. Detalhes com histórico muito longo pesam mais; o TTL de 1 h e o teto de 64 MB são os dois controles.

### Sem proteção contra estouro de cache (stampede)

Não há trava por chave nem renovação antecipada. Quando uma chave popular expira, as leituras simultâneas desse id vão todas ao Postgres e uma delas grava de novo. Neste volume o pior caso é um punhado de consultas por chave por hora (busca por chave primária, cerca de 0,1 ms medido) e o `statement_timeout` de 3 s da leitura pública limita o dano. Uma trava no Redis (`SET NX`) acrescentaria um modo de falha (trava presa, espera) para evitar o que hoje é barato. Revisar se a medição sob carga (Gatling S5, PR 2.7) mostrar fila de consultas iguais.

### Alternativas rejeitadas (cache)

- Cache de HTTP completo (corpo e ETag prontos): o ETag e a fase mudam com o relógio sem evento algum; guardar o resultado exigiria TTL curto ou invalidação por tempo.
- `@Cacheable` do Spring: esconde a chave, o TTL e o tratamento de falha (o padrão propaga a exceção do Redis), e o caso de uso passaria a conhecer o cache.
- Invalidar no ouvinte do Kafka antes do commit, ou por `@TransactionalEventListener`: a primeira corre com uma leitura concorrente; a segunda exigiria publicar eventos de aplicação dentro do store só para isto.
- Cache negativo: ver acima.

### Consequências (cache)

- A taxa de acerto medida com ids concentrados (Zipf, 2 000 detalhes) foi de 91%, mas **a latência de uma leitura isolada não melhora**: Postgres e Redis respondem em torno de 0,1 ms na mesma máquina, e as faltas custam mais. O ganho esperado é de carga no Postgres sob concorrência, que só o Gatling S5 (PR 2.7) mede; até lá o cache está ligado por decisão de arquitetura (menos leituras repetidas na base que a projeção e a busca também usam), não por ganho de latência provado.
- Um `DEL` perdido (Redis fora na hora do commit) deixa o detalhe antigo até o TTL de 1 h, se o Redis voltar com os dados. O runbook traz o comando que limpa o espaço.
- Qualquer mudança em `ProcurementDetail` (inclusive campo novo) exige subir o `v1` da chave, por causa das implantações graduais.

## Prova

- `core/src/test/java/br/dev/miguelsanzbr/radar/identity/domain/TokenBucketPropertiesTest.java` (jqwik) e `RedisRateLimiterParityIT`.
- `RedisRateLimiterIT`: 60 permitidas e a 61ª negada, baldes independentes, 50 chamadas paralelas deixam passar exatamente a capacidade, relógio que recua.
- `RateLimitFlowIT`: limite anônimo e por chave, /64 de IPv6, chave inválida gasta o balde do endereço, 429 com `Retry-After` conforme o contrato, chave revogada é 401, chave não autentica rota com papel. `RateLimitFailOpenIT`: Redis pausado, leituras passam e o contador sobe. `RateLimitRedisDownIT`: Redis parado, p95 abaixo de 50 ms. `RedisComposeTest`: política `volatile-ttl`.
- Cache: `RedisDetailCacheTest` (chave, TTL, acerto e falta, serialização de ida e volta, falha aberta, contador, WARN uma vez por minuto), `RedisDetailCacheIT` (chave e TTL no Redis real, `UNLINK` em lotes só do espaço do cache), `DetailCacheIT` (1º GET falta e 2º acerto sem consultar o Postgres, ETag acompanha a fase com o detalhe em cache, `Updated`, `SituationChanged`, `Closed` e `Discovered` apagam a chave depois do commit e não antes, transação desfeita não apaga, replay limpa o espaço), `DetailCacheRedisDownIT` (Redis parado), `DetailCacheConfigurationTest` (flag e TTL) e `DetailCacheBenchIT` (`docs/benchmarks/redis/hit-ratio.md`).
