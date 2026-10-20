# ADR-0017: Resiliência do cliente PNCP

- Status: aceita
- Data: 2026-10-20

## Contexto

O PNCP é uma API pública, sem autenticação e sem limite de taxa documentado no OpenAPI. A verificação manual de 22/10/2026, registrada no plano (seção 1), mostrou resposta 200 em cerca de 0,22 s e nenhum cabeçalho de limite de taxa; os horários dos campos de data vêm sem offset. O 204 para janela sem dados é o comportamento esperado do cliente, ainda sem confirmação pela sonda. O cliente roda em segundo plano, em volume alto durante o backfill, e precisa ser um usuário educado de um serviço público: identificar-se, limitar a taxa e recuar quando o serviço reclamar.

A sonda de 24 horas (tarefa 0.2) ainda não rodou: não existe `docs/benchmarks/probe/*.md`. Os valores abaixo são os padrões do plano e **serão revisados quando o relatório da sonda existir** (taxa, comportamento do 204, cabeçalhos e fuso). Isso vale em especial para a taxa, que o plano define como 50% do limite observado se a sonda achar algum. A revisão e o recorte de dados ficam no ADR-0005, a escrever com os números da sonda.

## Decisão

Cliente `java.net.http.HttpClient` com executor de threads virtuais, Jackson para o JSON e Resilience4j (módulos de núcleo, sem starter) configurado por `radar.pncp.*`. Cada tentativa passa, de fora para dentro, por retry, rate limiter, bulkhead e circuit breaker. O disjuntor só enxerga a chamada em si, então a espera por permissão de taxa ou vaga não conta como lentidão.

| Item | Valor padrão | Por quê |
|---|---|---|
| URL base | `https://pncp.gov.br` | API de consulta pública |
| Tamanho de página | 50 | máximo aceito (10 a 50): menos chamadas por janela |
| Taxa | 1 req/s | padrão do plano, bem abaixo de qualquer limite razoável; a sonda confirma ou reduz |
| Retry | 4 tentativas (a 1ª incluída), backoff exponencial de 1 s, multiplicador 2, jitter 0,5 | absorve falha transitória sem sincronizar clientes |
| Retentável | falha de transporte, timeout, 5xx e 429 | só o que pode dar certo depois |
| `Retry-After` no 429 | espera o maior entre o backoff e o valor do cabeçalho, em segundos, com teto de 300 s | respeita o pedido do servidor sem travar uma thread por tempo indefinido |
| Não retentável | demais 4xx e erro de mapeamento | repetir não muda o resultado; `PncpProtocolException` traz status e trecho do corpo |
| Disjuntor | janela de 20 chamadas (mínimo de 20), falha em 50%, aberto por 60 s, chamada lenta acima de 10 s; a taxa de chamadas lentas que abre o circuito é a mesma de falhas (50%) | para de martelar um serviço fora do ar, ou muito lento, e volta a testar sozinho |
| O que o disjuntor conta | falhas retentáveis | um 400 por erro nosso não deve abrir o circuito |
| Bulkhead | 4 chamadas simultâneas, espera de até 30 s por vaga | limita a concorrência da coleta; excedente espera |
| Espera por permissão de taxa | até 60 s | com taxa de 1 req/s a fila normal é de poucos segundos |
| Timeout | 15 s por requisição (também limita a leitura do corpo, verificado por teste), 5 s para conectar | a verificação manual mediu cerca de 0,22 s em chamada normal; 15 s cobre cauda longa |
| User-Agent | `radar-licitacoes/0.1 (+https://github.com/miguelsanzbr/radar-licitacoes; contato: miguelsanzb.p@gmail.com)` | identificável, com contato para o operador do PNCP |

Horários do PNCP vêm sem offset e são locais de `America/Sao_Paulo`. O cliente lê como `LocalDateTime` e converte com essa zona. Valor com offset ou `Z` falha o mapeamento com exceção clara, porque indica mudança na API. Valores decimais são limitados antes de virar `Money` (no máximo 18 dígitos, escala entre -2 e 2), pois `1E999999999` explodiria o `setScale`. Janela sem dados (HTTP 204) vira página vazia com zero páginas.

Métricas: contador `pncp_items_fetched_total` por endpoint, timer `pncp_request_duration` por endpoint e resultado, e os medidores do Resilience4j ligados ao Micrometer.

Etiqueta de coleta: a taxa acima vale para todo o tráfego do cliente. O backfill roda na janela noturna (0h às 6h), com janelas de um dia por vez e páginas de 50, para não competir com o pico de uso do PNCP.

## Alternativas rejeitadas (e por quê)

- Starter do Resilience4j para Spring Boot: traz anotações e AOP que o projeto não usa e esconde a ordem dos decoradores, que aqui importa. Os módulos de núcleo bastam.
- Retry mais interno que o limitador de taxa: a nova tentativa passaria sem pagar o limitador, e a taxa real ficaria acima da configurada.
- Contar todo erro no disjuntor: um 400 por parâmetro inválido abriria o circuito e derrubaria chamadas válidas.
- Bulkhead que rejeita o excedente: obrigaria quem chama a tratar rejeição; esperar é suficiente para uma coleta em lote.
- Cliente HTTP do Spring (`RestClient`): acrescenta uma camada sobre o `HttpClient` sem entregar nada que o cliente precise aqui.

## Consequências

- Os padrões são conservadores e podem ser mudados por configuração, sem recompilar. Quando a sonda rodar, o ADR-0005 registra o que mudou.
- Quem chama enxerga só dois tipos de falha, ambos em `ingest.application.port`, sem tipo de biblioteca ou de adapter: `PncpUnavailableException` (tentativas esgotadas, circuito aberto, rejeição local por taxa ou concorrência, falha de transporte, timeout, 5xx ou 429; vale tentar de novo mais tarde) e `PncpProtocolException` (4xx diferente de 429, ou resposta fora do formato esperado; repetir não ajuda). Os dois trazem o status HTTP quando existe (0 quando não). As exceções do Resilience4j são convertidas na fronteira do cliente e ficam como causa.
- Os parâmetros de `radar.pncp.*` são validados na construção (taxa positiva, tentativas e janela maiores ou iguais a 1, timeouts positivos); valor inválido impede a aplicação de subir.
- O `Retry-After` em formato de data HTTP é ignorado (atalho registrado no código); o PNCP não usa esse formato até agora.
- Testes de caos com Toxiproxy (latência, corte de conexão, perda de pacotes) entram na PR 4.4 e podem ajustar estes valores.

## Prova

- `core/src/test/java/br/dev/miguelsanzbr/radar/ingest/adapter/out/pncp/PncpHttpGatewayTest.java` e `PncpMapperTest.java`.
- Tempos e contagem de tentativas por cenário: `docs/benchmarks/pncp-client/resilience-scenarios.md`.
