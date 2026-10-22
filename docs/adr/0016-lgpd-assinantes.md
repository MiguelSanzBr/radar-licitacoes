# ADR-0016: dados pessoais de assinantes (LGPD): consentimento, verificação, exclusão e retenção

- Status: aceita
- Data: 2026-10-21

## Contexto

O contexto de alertas guarda o e-mail de quem pede avisos de licitações. É dado pessoal, então a Lei Geral de Proteção de Dados (LGPD) exige base legal, finalidade clara, minimização, retenção limitada e um caminho simples para o titular apagar seus dados. Ao mesmo tempo, a deduplicação de alertas depende de `alert_delivery`, cuja chave `(saved_search_id, procurement_id)` precisa continuar íntegra: apagar linhas de entrega já enviadas poderia reenviar o mesmo alerta.

## Decisão

**Dados guardados.** Em `alerts.subscriber`: e-mail, instante do consentimento, instante da verificação, instante da exclusão e instante da criação. Em `alerts.saved_search`: nome, texto, filtros, frequência e estado. Em `alerts.alert_delivery`: qual licitação casou com qual busca e quando foi enviada. Em `alerts.idempotency_key`: chave do cliente, hash do corpo e a resposta devolvida, por 24 horas. Em `alerts.verification_token`: só o SHA-256 do token. Nada além disso: nem nome, nem IP, nem agente do navegador.

**Base legal.** Consentimento (art. 7, I). O formulário `/assinatura` exige a caixa "concordo em receber e-mails" marcada e a API recusa o cadastro sem consentimento. A página `/privacidade` resume os dados e como apagá-los, e o formulário aponta para ela.

**Verificação por link.** O cadastro cria um token aleatório de 32 bytes em base64url, enviado no link. Só o hash SHA-256 fica no banco, então um vazamento da tabela não permite confirmar cadastros. O token vale 48 horas e só pode ser usado uma vez. Alertas só saem para e-mail verificado (regra aplicada pelo notificador, PR 3.3).

**Exclusão (`DELETE /api/v1/me`).** Anonimiza, não apaga a linha:
- o e-mail vira `deleted-<id>@invalid` e `deleted_at` é gravado, numa transação só;
- todas as buscas salvas são desativadas e perdem o nome (viram `(apagada)`) e o texto (fica vazio), o que impede que texto livre digitado pelo titular continue no banco;
- as linhas de `alert_delivery` ainda não enviadas, as chaves de idempotência e os tokens do assinante são apagados;
- um assinante excluído não verifica nem cria buscas;
- o mesmo e-mail pode se cadastrar de novo depois, como assinante novo, porque o endereço original deixou de existir na tabela.

O que permanece e por quê: a linha anônima de `subscriber`, as buscas desativadas e já vazias (só filtros e frequência, sem texto nem nome) e as entregas já enviadas. Elas preservam a integridade das chaves de deduplicação e o histórico de entrega. Nenhuma delas liga a uma pessoa depois da anonimização.

**Retenção.** Cadastros sem verificação são apagados de verdade (linha, buscas, tokens e chaves) 7 dias depois da criação, por um job diário (`radar.alerts.purge-cron`, padrão 03:15). O mesmo job apaga chaves de idempotência com mais de 24 horas. Os dois são idempotentes, então rodar em mais de uma instância não causa dano.

**E-mail.** No MVP o envio acontece só por Mailpit no ambiente local (PR 3.3). Nenhum provedor externo recebe dados de assinantes. Até lá, o envio do link de verificação é uma porta (`VerificationMailer`) com implementação que só registra o evento, sem imprimir o e-mail nem o token.

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
