# Cursor inválido

- Status HTTP: 400
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/invalid-cursor.md`

## Quando acontece

O valor de `cursor` não pôde ser decodificado ou foi adulterado. O cursor é opaco: só vale o que o servidor devolveu em `nextCursor`.

## O que fazer

Recomece a paginação sem `cursor` e siga apenas os `nextCursor` devolvidos.
