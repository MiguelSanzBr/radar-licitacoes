# Reprocessamento interrompido

- Status HTTP: 503
- Tipo: `https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/reprocess-interrupted.md`

## Quando acontece

O reenvio de mensagens da DLQ (`POST /ops/dlq/{topic}/reprocess`) parou porque o broker não aceitou uma mensagem. O reenvio não tenta as mensagens seguintes. O campo `count` diz quantas voltaram ao tópico original antes da falha; a que falhou continua pendente, com o motivo em `ops.dlq_message.reprocess_result`.

## O que fazer

Confira o estado do Kafka (painel `/ops/painel`, componente `consumers`) e repita a chamada quando o broker voltar. As mensagens que já voltaram não são reenviadas, porque estão marcadas. Se a mesma mensagem falhar sempre, as nunca tentadas passam à frente dela, mas a página que a alcança para nela: para pular a mensagem que falha, reprocesse as outras com `?id=`.
