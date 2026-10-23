# ADR-0016: dados pessoais de assinantes (LGPD): consentimento, verificação, exclusão e retenção

- Status: aceita
- Data: 2026-10-21

## Contexto

O contexto de alertas guarda o e-mail de quem pede avisos de licitações. É dado pessoal, então a Lei Geral de Proteção de Dados (LGPD) exige base legal, finalidade clara, minimização, retenção limitada e um caminho simples para o titular apagar seus dados. Ao mesmo tempo, a deduplicação de alertas depende de `alert_delivery`, cuja chave `(saved_search_id, procurement_id)` precisa continuar íntegra: apagar linhas de entrega já enviadas poderia reenviar o mesmo alerta.

## Decisão

**Dados guardados.** Em `alerts.subscriber`: e-mail, instante do consentimento, instante da verificação, instante da exclusão e instante da criação. Em `alerts.saved_search`: nome, texto, filtros, frequência e estado. Em `alerts.alert_delivery`: qual licitação casou com qual busca e quando foi enviada. Para buscas diárias, também o resumo público do edital (objeto, órgão, local, valor, encerramento e caminho), que o resumo diário mostra; nada disso é dado pessoal. Em `alerts.idempotency_key`: chave do cliente, hash do corpo e a resposta devolvida, por 24 horas. Em `alerts.verification_token`: só o SHA-256 do token. Nada além disso: nem nome, nem IP, nem agente do navegador.

**Base legal.** Consentimento (art. 7, I). O formulário `/assinatura` exige a caixa "concordo em receber e-mails" marcada e a API recusa o cadastro sem consentimento. A página `/privacidade` resume os dados e como apagá-los, e o formulário aponta para ela.

**Verificação por link.** O cadastro cria um token aleatório de 32 bytes em base64url, enviado no link. Só o hash SHA-256 fica no banco, então um vazamento da tabela não permite confirmar cadastros. O token vale 48 horas e só pode ser usado uma vez. Alertas só saem para e-mail verificado (regra aplicada pelo notificador, PR 3.3, ver "Envio e deduplicação").

**Exclusão (`DELETE /api/v1/me`).** Anonimiza, não apaga a linha:
- o e-mail vira `deleted-<id>@invalid` e `deleted_at` é gravado, numa transação só;
- todas as buscas salvas são desativadas e perdem o nome (viram `(apagada)`) e o texto (fica vazio), o que impede que texto livre digitado pelo titular continue no banco;
- as linhas de `alert_delivery` ainda não enviadas, as chaves de idempotência e os tokens do assinante são apagados;
- um assinante excluído não verifica nem cria buscas;
- o mesmo e-mail pode se cadastrar de novo depois, como assinante novo, porque o endereço original deixou de existir na tabela.

O que permanece e por quê: a linha anônima de `subscriber`, as buscas desativadas e já vazias (só filtros e frequência, sem texto nem nome) e as entregas já enviadas. Elas preservam a integridade das chaves de deduplicação e o histórico de entrega. Nenhuma delas liga a uma pessoa depois da anonimização.

**Retenção.** Cadastros sem verificação são apagados de verdade (linha, buscas, tokens e chaves) 7 dias depois da criação, por um job diário (`radar.alerts.purge-cron`, padrão 03:15). O mesmo job apaga chaves de idempotência com mais de 24 horas. Os dois são idempotentes, então rodar em mais de uma instância não causa dano.

**E-mail.** No MVP o envio acontece só pelo SMTP do ambiente (Mailpit no compose, `SPRING_MAIL_HOST` e `SPRING_MAIL_PORT`); nenhum provedor externo recebe dados de assinantes. Três mensagens saem pelo Spring Mail, todas em pt-BR, com corpo HTML (escapado pelo Thymeleaf, porque o texto do portal não é confiável) e alternativa em texto puro, a partir de `templates/mail`:
- verificação: link `${radar.public-base-url}/assinatura/confirmar?token=...`, a mesma rota da página de confirmação (o plano citava `/assinatura/verificar`, mas a rota que existe e já tem teste é `/confirmar`, e um link que não abre seria pior que um nome diferente do plano);
- alerta instantâneo, um por casamento de busca `INSTANT`, com assunto `Nova licitação para a busca "<nome>"`, objeto resumido, órgão, UF e município, valor em reais, encerramento no fuso de São Paulo e link absoluto do edital;
- resumo diário (`radar.alerts.digest.cron`, padrão `0 0 7 * * *` em `America/Sao_Paulo`, ligado por `radar.alerts.digest.enabled`): um e-mail por assinante com todos os casamentos `DAILY` ainda não enviados, agrupados por busca.

Todo alerta termina com links para gerenciar buscas e para a página de privacidade, onde está o caminho de exclusão dos dados. Só recebe e-mail quem está verificado, não excluído, com a busca ativa: a mesma regra do casamento, aplicada de novo na hora do envio, para que uma exclusão entre o casamento e o envio impeça a mensagem.

**Envio e deduplicação (at-least-once).** O notificador consome `radar.alert.v1` no grupo `alerts-notifier`. Para `INSTANT`: lê a linha de `alert_delivery`; se `sent_at` já existe, não envia (um evento reentregue nunca gera o segundo e-mail); senão envia, e só depois que o servidor SMTP aceitou grava `sent_at` numa transação curta, junto com o registro do evento no inbox. Se a gravação falhar depois do envio, a reentrega do evento reenvia o e-mail: essa janela é aceita e documentada. Um banco instável (falhando e voltando várias vezes logo depois do aceite do SMTP) pode repetir isso e gerar várias cópias do mesmo alerta, uma por reentrega; o custo é e-mail repetido, nunca e-mail perdido, e por isso foi aceito. A alternativa (gravar antes de enviar) perde o e-mail se o processo cair entre os dois passos, o que é pior para um alerta. Para `DAILY`, o evento só guarda na própria linha o que o resumo mostra (colunas da migração V11), sem enviar nada; o resumo marca todas as linhas de um assinante como enviadas numa transação depois de o SMTP aceitar a mensagem. Ao marcar como enviada, a linha apaga as colunas do resumo (objeto, órgão, local, valor, encerramento, caminho): a tabela fica menor e nada além do necessário permanece guardado. O resumo não tem trava entre instâncias: assume uma única instância da aplicação; duas instâncias rodando no mesmo horário enviariam o resumo duas vezes. Se a aplicação passar a escalar horizontalmente, a trava entra (por exemplo `pg_try_advisory_lock` em volta da execução).

**Falha de SMTP.** O ouvinte tem fábrica de contêiner própria: 3 novas tentativas com espera dobrando (1 s, 2 s, 4 s), ou seja, 4 execuções no total, e depois o registro vai para `radar.alert.v1.dlq` pelo mesmo recuperador de todos os consumidores, com os cabeçalhos `x-error-class`, `x-error-message`, `x-original-topic`, `x-original-partition`, `x-original-offset` e `x-failed-at` (ADR-0026). Mensagem ilegível vai para a DLQ sem tentativas; queda do banco é tentada sem limite, como no casamento, e dado que o banco rejeita tem as 3 tentativas e vai para a DLQ. No resumo, o assinante cujo e-mail o servidor recusa fica na fila para a execução seguinte e não atrasa os demais. `sent_at` nunca é gravado sem aceite do servidor. Cada recusa do servidor (em cada tentativa) incrementa `alert_mail_failures_total{kind="instant|digest|verification"}`. No cadastro, a recusa devolve `503` com o problema `mail-unavailable` (`docs/problems/mail-unavailable.md`); o cadastro já está gravado, então repetir o pedido reenvia o link.

**Latência.** `alert_delivery_latency_seconds` (histograma) mede do casamento (`matched_at`) até o aceite do SMTP, só para alertas instantâneos: é a medida do SLO "Alerta" (ADR-0025). O resumo diário não entra, porque a espera até as 7 h é decisão do assinante, não latência. O histograma tem um balde exatamente em 60 s (o alvo) e limite máximo de 5 minutos. O notificador também ignora alertas mais velhos que `radar.alerts.max-event-age` (a mesma propriedade do casamento, padrão 1 dia), contando em `alert_stale_events_total`: um atraso longo do consumidor não vira e-mail velho.

**Novo cadastro do mesmo e-mail.** Se o endereço existe e nunca foi verificado, o cadastro reaproveita a linha, troca o token (o anterior deixa de valer), reenvia o link e responde 202: quem perdeu o e-mail pode pedir de novo. Se o endereço já foi verificado, a resposta é `409`. Troca aceita: o `409` revela que aquele e-mail já é assinante (enumeração de e-mails). Mantivemos porque o cadastro por formulário precisa dizer ao titular por que nada chegou e o risco é baixo (só confirma a existência de uma assinatura de alertas de licitação). Mitigação futura, se necessário: responder 202 sempre e avisar o titular por e-mail.

**Unicidade do e-mail.** A coluna é `citext UNIQUE`: `Fulano@Example.com` e `fulano@example.com` são o mesmo assinante. O domínio já normaliza para minúsculas; o banco é a segunda barreira e responde `409` com problema `conflict`.

## Alternativas rejeitadas (e por quê)

- Apagar a linha do assinante na exclusão: quebraria a chave estrangeira das buscas e das entregas, e a perda de `alert_delivery` enviadas permitiria reenviar alertas antigos.
- Guardar o token de verificação em texto: um vazamento da tabela confirmaria cadastros alheios.
- Verificação opcional: sem confirmar o e-mail, qualquer pessoa poderia inscrever o endereço de outra.
- Retenção indefinida de cadastros não verificados: guarda dado pessoal sem finalidade.

## Consequências

- A tabela `subscriber` cresce com linhas anônimas; o volume esperado é pequeno e a linha é leve.
- O link de confirmação é uma requisição `GET` com efeito. Ferramentas de leitura de e-mail que pré-visitam links podem consumir o token; o usuário se cadastra de novo. Uma página com botão `POST` fica como melhoria se isso aparecer na prática.
- Desde a PR 5.1 a identidade vem do login no Keycloak (ADR-0019): o cadastro em `/assinatura` exige sessão, usa o e-mail da conta e liga o assinante ao `sub` do token. A exclusão também desfaz esse vínculo.

## Prova

- `core/src/test/java/br/dev/miguelsanzbr/radar/alerts/adapter/AlertsFlowIT.java`: `deleteMeAnonymizesDeactivatesAndCleansUp`, `registrationStoresOnlyTheTokenHashAndTheLinkWorksOnce`, `expiredTokenDoesNotVerify`, `emailsThatDifferOnlyByCaseCollideWith409`, `dailyPurgeDropsOldUnverifiedSubscribersAndStaleKeys`.
- `core/src/test/java/br/dev/miguelsanzbr/radar/alerts/domain/SubscriberTest.java`: exclusão anonimiza e assinante excluído não verifica.
- `core/src/test/java/br/dev/miguelsanzbr/radar/alerts/adapter/EmailNotifierIT.java` (Kafka, Spring Mail e Mailpit reais, lido pela API REST do Mailpit): `anInstantAlertArrivesOnceWithTheProcurementLinkAndMarksTheDeliverySent`, `theSameEventDeliveredAgainStillProducesOneMail`, `dailyAlertsWaitForTheDigestThatSendsOneMailPerSubscriber`, `anUnreachableMailServerIsRetriedThreeTimesThenTheEventGoesToTheDeadLetterTopic`.
- `core/src/test/java/br/dev/miguelsanzbr/radar/alerts/adapter/VerificationEmailIT.java`: o link do e-mail enviado ao Mailpit confirma o assinante.
- `core/src/test/java/br/dev/miguelsanzbr/radar/alerts/adapter/out/mail/MailTemplatesTest.java`: formato de moeda e data em pt-BR, escape de HTML e links absolutos.
