# Serviço indisponível

- Status HTTP: 503
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/unavailable.md`

## Quando acontece

Uma consulta pública passou do tempo máximo (`radar.search.statement-timeout`, 3 segundos por padrão) e foi cancelada para não prender uma conexão do banco. A resposta traz `Retry-After`.

O mesmo tipo sai quando um token Bearer não pôde ser validado porque as chaves do provedor de identidade estão inalcançáveis: o token não foi julgado, então não é 401. A resposta traz `Retry-After: 5`; repita depois desse prazo.

## O que fazer

Tente de novo depois do tempo de `Retry-After`. No caso do token, o mesmo token vale de novo quando o provedor voltar. Se a consulta é sempre lenta, estreite os filtros (`uf`, `modality`, faixa de valor).
