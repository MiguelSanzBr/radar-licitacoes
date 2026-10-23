# Serviço indisponível

- Status HTTP: 503
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/unavailable.md`

## Quando acontece

Uma consulta pública passou do tempo máximo (`radar.search.statement-timeout`, 3 segundos por padrão) e foi cancelada para não prender uma conexão do banco. A resposta traz `Retry-After`.

## O que fazer

Tente de novo depois do tempo de `Retry-After`. Se a consulta é sempre lenta, estreite os filtros (`uf`, `modality`, faixa de valor).
