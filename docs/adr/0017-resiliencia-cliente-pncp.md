# ADR-0017: Resiliência do cliente PNCP

- Status: aceita
- Data: 2026-10-20
- Revisão: 2026-10-23, taxa final com a evidência da sonda

## Contexto

O PNCP é uma API pública, sem autenticação e sem limite de taxa documentado no OpenAPI. A verificação manual de 22/10/2026, registrada no plano (seção 1), mostrou resposta 200 em cerca de 0,22 s e nenhum cabeçalho de limite de taxa; os horários dos campos de data vêm sem offset. O 204 para janela sem dados foi confirmado pela sonda (302 respostas 204 em 2.646 requisições). O cliente roda em segundo plano, em volume alto durante o backfill, e precisa ser um usuário educado de um serviço público: identificar-se, limitar a taxa e recuar quando o serviço reclamar.

A sonda rodou em 22/10/2026 e o relatório está em `docs/benchmarks/probe/2026-10-22.md`: três execuções, 2.646 requisições, parada na primeira resposta 429. Ela **não será retomada**, então a janela de 24 h e a rampa de taxa (S3) ficaram sem medida. O que ela provou para este cliente: nenhuma resposta traz cabeçalho de limite (`RateLimit-*`, `Retry-After`); 204 para janela sem dados é normal; os timestamps vêm sem offset (0 de 11.800); a latência é de p50 entre 95 ms e 131 ms e p99 entre 4,4 s e 7,0 s nas horas 17 a 19, com páginas fundas chegando a 12,8 s; houve 10 respostas 503 seguidas em 18 s; e a API devolveu um 429. O recorte de dados e a etiqueta de coleta estão no ADR-0005.

## Decisão

Cliente `java.net.http.HttpClient` com executor de threads virtuais, Jackson para o JSON e Resilience4j (módulos de núcleo, sem starter) configurado por `radar.pncp.*`. Cada tentativa passa, de fora para dentro, por retry, rate limiter, bulkhead e circuit breaker. O disjuntor só enxerga a chamada em si, então a espera por permissão de taxa ou vaga não conta como lentidão.

| Item | Valor padrão | Por quê |
|---|---|---|
| URL base | `https://pncp.gov.br` | API de consulta pública |
| Tamanho de página | 50 | máximo aceito (10 a 50): menos chamadas por janela |
| Taxa | 0,2 req/s (1 requisição a cada 5 s) | **final**: a API real respondeu 429 (corpo HTML, sem `Retry-After`) a 0,5 req/s sustentados, então 0,2 req/s fica abaixo da metade da taxa em que a falha apareceu (seção "Taxa e o 429"); o padrão de 1 req/s do plano não vale |
| Retry | 4 tentativas (a 1ª incluída), backoff exponencial de 1 s, multiplicador 2, jitter 0,5 | absorve falha transitória sem sincronizar clientes |
| Retentável | falha de transporte, timeout, 5xx e 429 | só o que pode dar certo depois |
| `Retry-After` no 429 | espera o maior entre o backoff e o valor do cabeçalho, em segundos, com teto de 300 s | respeita o pedido do servidor sem travar uma thread por tempo indefinido |
| Não retentável | demais 4xx e erro de mapeamento | repetir não muda o resultado; `PncpProtocolException` traz status e trecho do corpo |
| Disjuntor | janela de 20 chamadas (mínimo de 20), falha em 50%, aberto por 60 s, chamada lenta acima de 10 s; a taxa de chamadas lentas que abre o circuito é a mesma de falhas (50%) | para de martelar um serviço fora do ar, ou muito lento, e volta a testar sozinho |
| O que o disjuntor conta | falhas retentáveis | um 400 por erro nosso não deve abrir o circuito |
| Bulkhead | 4 chamadas simultâneas, espera de até 30 s por vaga | limita a concorrência da coleta; excedente espera |
| Espera por permissão de taxa | até 120 s | a 0,2 req/s cada permissão custa 5 s; a espera cobre cerca de 24 chamadas na fila (a leitura adiantada do backfill põe até 2, a sincronização incremental outras 2, mais as novas tentativas) |
| Timeout | 15 s por requisição (também limita a leitura do corpo, verificado por teste), 5 s para conectar | a verificação manual mediu cerca de 0,22 s em chamada normal. A sonda mediu p50 de 95 a 131 ms, p99 de 4,4 a 7,0 s e, nas páginas fundas, máximo de 12,8 s (atualização) e 10,8 s (publicação): 15 s cobre a cauda vista, com pouca folga. Acima de 10 s a chamada conta como lenta para o disjuntor |
| User-Agent | `radar-licitacoes/0.1 (+https://github.com/miguelsanzbr/radar-licitacoes; contato: miguelsanzb.p@gmail.com)` | identificável, com contato para o operador do PNCP |

Horários do PNCP vêm sem offset e são locais de `America/Sao_Paulo`. O cliente lê como `LocalDateTime` e converte com essa zona. Valor com offset ou `Z` falha o mapeamento com exceção clara, porque indica mudança na API. Valores decimais são limitados antes de virar `Money` (no máximo 18 dígitos, escala entre -2 e 2), pois `1E999999999` explodiria o `setScale`. Janela sem dados (HTTP 204) vira página vazia com zero páginas.

Métricas: contador `pncp_items_fetched_total` por endpoint, timer `pncp_request_duration` por endpoint e resultado, e os medidores do Resilience4j ligados ao Micrometer.

Etiqueta de coleta: a taxa acima vale para todo o tráfego do cliente. O backfill roda na janela noturna (0h às 6h), com janelas de um dia por vez e páginas de 50, para não competir com o pico de uso do PNCP.

## Taxa e o 429

Evidência (relatório da sonda e leitura do JSONL, ambos descritos lá):

- Às 19:44 do dia 22/10, depois de 2 h 42 de coleta (2.646 requisições, três execuções), a API respondeu HTTP 429 a uma requisição `publicacao` de 10 itens. A resposta veio em 52 ms, como `text/html` de 460 bytes, com `connection: close`, sem `Retry-After` e sem `RateLimit-*`. Parece a página de um WAF, não uma resposta da API de dados.
- A taxa nominal era 0,5 req/s (uma requisição a cada 2 s): foram 286 requisições nos 10 minutos anteriores (0,48 req/s) e as três últimas estavam espaçadas por exatamente 2,0 s. A mesma taxa vinha sendo aceita havia mais de 2 horas.
- Quinze minutos antes (19:29:03 a 19:29:21), dez requisições seguidas, em endpoints diferentes, tinham recebido 503.
- Só esse 429 existe. A rampa S3 (de 0,5 para 4 req/s) não rodou, então o limite real, a regra que o disparou (taxa, volume do dia, reação ao 503 ou outra) e o tempo de bloqueio são desconhecidos.

Decisão: **0,2 req/s, definitivo.** É 40% da taxa em que a falha apareceu e fica abaixo da metade dela (0,25 req/s), a regra do plano para quando se observa o limite. Como o limite real pode ser menor que 0,5 req/s (a falha veio depois de horas, não no primeiro minuto), essa escolha é uma aposta com margem, não um valor provado. Regra de operação: se a coleta tomar 429 mesmo a 0,2 req/s, a taxa baixa e a mudança vira ADR; ela nunca sobe sem medida nova, e a sonda não volta a rodar contra a API real.

Comportamento do cliente diante do 429, que **não é** o da sonda. A sonda para na primeira 429 ou 403. O cliente da aplicação trata 429 como falha retentável: tenta de novo com recuo exponencial (1 s, multiplicador 2, jitter 0,5), respeita `Retry-After` se vier (não veio nenhum na coleta) e, depois de 4 tentativas, lança `PncpUnavailableException`. No backfill a run vira `FAILED` e o intervalo para (ADR-0006). No incremental o ciclo falha e o seguinte começa 5 minutos depois. Cada tentativa paga uma permissão de 5 s do limitador, então as 4 tentativas levam pelo menos 15 s. O disjuntor (janela de 20 chamadas, 50% de falha) também pode abrir.

Pendência, sem código nesta mudança: diante de um bloqueio por origem como o visto, as 3 tentativas extras e o ciclo de 5 minutos continuam batendo na porta. Parar a coleta na primeira 429 sem `Retry-After` por um tempo longo (por exemplo, abrir o disjuntor por muito mais que 60 s) é uma mudança pequena de configuração e de código que deve ser avaliada numa PR própria.

## Alternativas rejeitadas (e por quê)

- Starter do Resilience4j para Spring Boot: traz anotações e AOP que o projeto não usa e esconde a ordem dos decoradores, que aqui importa. Os módulos de núcleo bastam.
- Retry mais interno que o limitador de taxa: a nova tentativa passaria sem pagar o limitador, e a taxa real ficaria acima da configurada.
- Contar todo erro no disjuntor: um 400 por parâmetro inválido abriria o circuito e derrubaria chamadas válidas.
- Bulkhead que rejeita o excedente: obrigaria quem chama a tratar rejeição; esperar é suficiente para uma coleta em lote.
- Cliente HTTP do Spring (`RestClient`): acrescenta uma camada sobre o `HttpClient` sem entregar nada que o cliente precise aqui.

## Consequências

- O limitador é a única fonte da taxa. A leitura de páginas em paralelo (ADR-0020) reduz latência, mas nunca eleva as requisições por segundo acima dele: cada tentativa, inclusive a nova tentativa, paga uma permissão antes de entrar no bulkhead.
- Permissões reservadas não voltam: o `RateLimiter` do Resilience4j reserva a permissão antes de a thread esperar, e uma espera interrompida (leitura adiantada cancelada porque uma página falhou ou a janela acabou) não devolve a reserva. As páginas canceladas continuam ocupando suas vagas no tempo (até `fetch-ahead` × 5 s a 0,2 req/s) e atrasam as chamadas seguintes; a taxa real só cai, nunca passa do limite. É o motivo de `radar.ingest.fetch-ahead` valer 2 por padrão.
- Os padrões são conservadores e podem ser mudados por configuração, sem recompilar. A sonda rodou: a taxa é final (seção "Taxa e o 429") e os demais valores seguem como padrão, sem medida de madrugada, e o ADR-0005 registra o recorte e a etiqueta.
- Quem chama enxerga só dois tipos de falha, ambos em `ingest.application.port`, sem tipo de biblioteca ou de adapter: `PncpUnavailableException` (tentativas esgotadas, circuito aberto, rejeição local por taxa ou concorrência, falha de transporte, timeout, 5xx ou 429; vale tentar de novo mais tarde) e `PncpProtocolException` (4xx diferente de 429, ou resposta fora do formato esperado; repetir não ajuda). Os dois trazem o status HTTP quando existe (0 quando não). As exceções do Resilience4j são convertidas na fronteira do cliente e ficam como causa.
- Os parâmetros de `radar.pncp.*` são validados na construção (taxa positiva, tentativas e janela maiores ou iguais a 1, timeouts positivos); valor inválido impede a aplicação de subir.
- O `Retry-After` em formato de data HTTP é ignorado (atalho registrado no código); o PNCP não usa esse formato até agora.
- Testes de caos com Toxiproxy (latência, corte de conexão, perda de pacotes) entram na PR 4.4 e podem ajustar estes valores.

## Prova

- `core/src/test/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/pncp/PncpHttpGatewayTest.java` e `PncpMapperTest.java`.
- Tempos e contagem de tentativas por cenário: `docs/benchmarks/pncp-client/resilience-scenarios.md`.

## Prova sob caos

A PR 4.4 pôs à prova a entrega do outbox ao Kafka e do Kafka à view de busca, com falhas reais: 10 mil avisos passam pela coleta, pelo outbox, pelo Kafka e pelo projetor enquanto o Toxiproxy e reinícios de contêiner derrubam, um por vez, o Postgres (latência de 2 s e reinício no meio de um lote do relay) e o Kafka (corte de 10 s, 5% das conexões derrubadas e reinício do broker). Resultado: zero perdas, zero efeitos duplicados e zero mensagens na DLQ; o projetor aplicou exatamente 10 000 eventos de 10 051 registros no tópico, e o inbox recusou os 51 a mais.

O que isso não cobre do cliente PNCP: a latência de 2 s no PNCP ficou dentro dos limites do cliente (timeout de 15 s), com o limitador de taxa elevado só para o teste, e não exercitou a nova tentativa nem o disjuntor. A taxa deste ADR é final; os demais valores seguem como padrão. Números, montagem e limites em `docs/benchmarks/chaos/2026-10-23.md`; rode com `make chaos`.
