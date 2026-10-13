# Chave de idempotência reutilizada

- Status HTTP: 422
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/idempotency-mismatch.md`

## Quando acontece

A `Idempotency-Key` já foi usada em outra chamada com um corpo diferente.

## O que fazer

Gere uma chave nova para cada operação distinta. Reenvie a mesma chave só para repetir exatamente a mesma operação.
