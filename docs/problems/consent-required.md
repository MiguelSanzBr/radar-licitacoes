# Consentimento necessário

- Status HTTP: 403
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/consent-required.md`

## Quando acontece

O usuário está autenticado com o papel `supplier`, mas a conta ainda não aceitou receber alertas, então não existe assinante ligado a ela. As rotas de buscas salvas precisam desse assinante.

## O que fazer

Abra `/assinatura` no navegador, entre com a mesma conta, marque "concordo em receber e-mails" e confirme o e-mail pelo link enviado. Depois repita a chamada.
