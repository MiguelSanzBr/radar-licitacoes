# ADR-0027: Redis: limite de requisições por balde de fichas (cache em PR 5.3)

- Status: proposta
- Data: 2026-10-22

## Contexto

A API pública de leitura é aberta e precisa de um teto de requisições por cliente, mais alto para quem tem chave de API (ADR-0019). O contador precisa ser compartilhado entre instâncias da aplicação e atômico. O Redis do compose (`redis:8.8-alpine`, sem persistência, 64 MB) já existe para isso. Esta ADR cobre a parte do limite; a PR 5.3 acrescenta o que cachear e o que não cachear.

## Decisão

**Balde de fichas por chave de API ou por endereço**, num script Lua (`core/src/main/resources/identity/token-bucket.lua`) executado com uma só chamada por requisição, então refilar, tirar uma ficha e responder são atômicos, mesmo com várias instâncias. Limites por minuto, de configuração: `free` 60, `pro` 600, anônimo (por endereço) 30. A capacidade é o limite e o balde refila a capacidade inteira em 60 s, continuamente (60 por minuto são uma ficha por segundo).

**Aritmética exata.** O nível é contado em 1/janela de ficha: um pedido custa a janela (60 000) e cada milissegundo decorrido devolve a capacidade. Tudo é inteiro, nada arredonda e o balde não empresta nem perde ficha. `TokenBucketModel` (fontes de teste) é a mesma conta em Java, serve de oráculo nas propriedades jqwik (nunca passa da capacidade mais o que refilou em qualquer trecho; rajada num instante deixa passar exatamente a capacidade) e é comparado ao script real em `RedisRateLimiterParityIT`, inclusive com relógios que andam para trás.

**Relógio.** O tempo vem do `TIME` do Redis, dentro do script: todas as instâncias leem o mesmo relógio e não há desvio entre elas. O script aceita um quarto argumento opcional com o "agora" em milissegundos, que só os testes passam (para mover o tempo à mão e comparar o script com o modelo em Java). Um tempo atrás do guardado (relógio de teste, ou failover para uma réplica levemente atrasada) não refila nem recua o balde.

**Memória do Redis.** Toda chave de balde tem TTL (a ficha cheia já equivale a chave ausente, então expira em `tempo até encher + 1 s`). O Redis roda com `--maxmemory-policy volatile-ttl`: com a memória cheia ele descarta as chaves que expiram primeiro, em vez de recusar escritas (o padrão `noeviction` faria todo script falhar com OOM e o limite abriria para todos). Isto vale para o compose e para qualquer Redis externo (Helm). Quem acrescentar chaves ao mesmo Redis (o cache da PR 5.3) precisa dar TTL a todas.

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
- A parte de cache (cache-aside do detalhe, por que não cachear a lista) é acrescentada na PR 5.3 e muda o status para aceita.

## Prova

- `core/src/test/java/br/dev/miguelsanzbr/radar/identity/domain/TokenBucketPropertiesTest.java` (jqwik) e `RedisRateLimiterParityIT`.
- `RedisRateLimiterIT`: 60 permitidas e a 61ª negada, baldes independentes, 50 chamadas paralelas deixam passar exatamente a capacidade, relógio que recua.
- `RateLimitFlowIT`: limite anônimo e por chave, /64 de IPv6, chave inválida gasta o balde do endereço, 429 com `Retry-After` conforme o contrato, chave revogada é 401, chave não autentica rota com papel. `RateLimitFailOpenIT`: Redis pausado, leituras passam e o contador sobe. `RateLimitRedisDownIT`: Redis parado, p95 abaixo de 50 ms. `RedisComposeTest`: política `volatile-ttl`.
