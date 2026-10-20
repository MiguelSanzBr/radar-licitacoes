# Cenários de resiliência do cliente PNCP

Data da medição: 2026-10-20. Servidor simulado: WireMock 3.13.2 em porta dinâmica. Cada cenário usa uma configuração reduzida para rodar rápido (backoff inicial de 10 ms, janela do disjuntor de 2 chamadas onde o cenário exige), com as mesmas regras da política padrão do [ADR-0017](../../adr/0017-resiliencia-cliente-pncp.md).

Comando:

```
./mvnw test -pl core -Dtest=PncpHttpGatewayTest
```

Os testes imprimem uma linha `SCENARIO|nome|requests=N|elapsedMs=T` por cenário. A coluna "requisições" conta o que o WireMock recebeu de fato, incluindo a chamada de aquecimento do cenário do limitador.

| # | Cenário | Esperado | Observado (requisições, tempo) |
|---|---|---|---|
| 1 | 200 paginado, páginas 1 e 2 | itens mapeados campo a campo, `isLast` falso na 1 e verdadeiro na 2, horários convertidos de `America/Sao_Paulo` | 2 requisições, 48 ms |
| 2 | 204 sem corpo | página vazia, `totalPages` 0, `isLast` verdadeiro | 1 requisição, 12 ms |
| 3 | 429 com `Retry-After: 1`, depois 200 | sucesso na 2ª tentativa, espera de pelo menos 1 s | 2 requisições, 1038 ms |
| 4 | 500, 500, 200 | sucesso na 3ª tentativa | 3 requisições, 96 ms |
| 4b | 503 em todas as tentativas | 4 tentativas, depois `PncpUnavailableException` com status 503 | 4 requisições, 110 ms |
| 5 | resposta com atraso de 15 s e timeout de 1 s; janela do disjuntor de 2 chamadas | 2 timeouts, circuito abre, 3ª chamada falha na hora com `PncpUnavailableException` (causa `CallNotPermittedException`) sem chegar ao servidor | 2 requisições, 2022 ms (2 timeouts de 1 s) |
| 6 | 5 chamadas a 2 req/s (após 1 chamada de aquecimento) | pelo menos 2 s | 6 requisições, 2507 ms |
| 7 | 6 chamadas simultâneas com bulkhead de 2 vagas | as excedentes esperam; no máximo 2 requisições simultâneas no servidor | 6 requisições, 513 ms, máximo simultâneo observado dentro do limite de 2 |
| 8 | horário com offset ou `Z`; `objetoCompra` ausente | falha de mapeamento sem retentativa; descrição vazia | coberto por `PncpMapperTest` e por `mappingFailuresAreNotRetried` (1 requisição) |
| 9 | 400 com corpo | `PncpProtocolException` com status e trecho do corpo, 1 requisição | 1 requisição, 37 ms |
| 10 | 500 e depois 204 a 2 req/s (a retentativa também paga o limitador) | 2ª tentativa começa pelo menos 450 ms depois da 1ª | 3 requisições (1 de aquecimento), 1016 ms, intervalo medido pelos horários do WireMock |
| 11 | corpo de resposta entregue em gotas por 10 s, timeout de 1 s | o timeout também interrompe a leitura do corpo | 1 requisição, 1004 ms |

Cenários extras, sem tempo impresso:

- `Retry-After` inválido (`soon`): cai no backoff exponencial, 2 requisições.
- Conexão derrubada pelo servidor (`CONNECTION_RESET_BY_PEER`): tratada como falha de transporte (status 0), retentada e depois levantada.
- Interrupção da thread no meio da chamada: levanta `CancellationException` e mantém o sinal de interrupção.
- `Retry-After` em segundos, com teto de 300 s, e demais formatos ignorados: teste unitário de `retryAfterMillis`.

Observação sobre o cenário 6: o limitador já nasce com uma permissão, então a primeira chamada passa sem espera e as 5 seguintes são espaçadas de 500 ms; o tempo medido parte da 2ª chamada.
