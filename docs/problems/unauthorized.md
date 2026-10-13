# Não autenticado

- Status HTTP: 401
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/unauthorized.md`

## Quando acontece

A credencial está ausente, é inválida ou expirou: token JWT nas rotas de `supplier` e `operator`, ou `X-Api-Key` malformada nas rotas públicas.

## O que fazer

Obtenha um token novo no provedor de identidade ou revise a chave de API.
