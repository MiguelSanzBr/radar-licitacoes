# Requisição inválida

- Status HTTP: 400 ou 422
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/validation.md`

## Quando acontece

Um parâmetro ou campo do corpo viola o contrato: formato errado, fora da faixa (por exemplo `limit` acima de 50) ou regra de negócio (por exemplo `minValue` maior que `maxValue`). O campo `errors` lista cada violação com `field` e `message`.

## O que fazer

Corrija os campos listados em `errors` e envie de novo. Repetir a mesma requisição sem mudança falha do mesmo jeito.
