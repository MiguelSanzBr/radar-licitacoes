# E-mail não verificado

- Status HTTP: 403
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/email-not-verified.md`

## Quando acontece

A conta tentou aceitar os alertas em `/assinatura`, mas o provedor de login (Keycloak) não marca o e-mail dela como verificado (`email_verified` ausente ou falso). Sem essa garantia, alguém poderia assinar alertas para um endereço que não é seu.

## O que fazer

Confirme o e-mail da conta no Keycloak (ou peça ao administrador) e repita o aceite.
