# E-mail indisponível

- Status HTTP: 503
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/mail-unavailable.md`

## Quando acontece

O cadastro foi registrado, mas o servidor de e-mail não aceitou a mensagem com o link de confirmação (fora do ar, recusando conexões ou lento demais).

## O que fazer

Envie o formulário de `/assinatura` de novo em alguns instantes. O cadastro anterior continua guardado e o pedido repetido troca o token e reenvia o link; o link antigo deixa de valer. Se o erro persistir, avise quem opera o serviço: o contador `alert_mail_failures_total{kind="verification"}` mostra as recusas.
