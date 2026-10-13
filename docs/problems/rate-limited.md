# Limite de requisições excedido

- Status HTTP: 429
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/rate-limited.md`

## Quando acontece

O cliente passou do limite de requisições da janela atual. A resposta traz `Retry-After` (segundos) e os cabeçalhos `RateLimit-Limit`, `RateLimit-Remaining` e `RateLimit-Reset`.

## O que fazer

Espere `Retry-After` segundos. Uma chave de API em `X-Api-Key` eleva o limite das rotas públicas.
