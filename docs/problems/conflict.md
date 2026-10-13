# Conflito

- Status HTTP: 409
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/conflict.md`

## Quando acontece

A operação conflita com o estado atual: uma chamada com a mesma `Idempotency-Key` ainda está em andamento, ou uma operação de manutenção (replay, coleta histórica) já está rodando.

## O que fazer

Espere a operação em andamento terminar e consulte o resultado antes de tentar de novo.
