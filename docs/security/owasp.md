# Checklist OWASP Top 10 (2021)

Uma seção por categoria. Cada uma diz o risco neste sistema, a mitigação (com o arquivo) e o teste que a prova, ou o motivo de não haver teste. Um item só aparece como coberto quando há teste que falha se a mitigação for retirada; o resto está marcado como **pendente** com o motivo. Os testes novos deste PR foram conferidos retirando a mitigação de propósito (a prova está no relatório do PR).

Convenções: os testes ficam em `core/src/test/java/br/dev/miguelsanzbr/radar/`; `IT` roda no failsafe com Postgres compartilhado. Ver também `docs/adr/0019-seguranca.md`, `docs/security/authz-matrix.md` e `docs/security/scans.md`.

## A01 Controle de acesso quebrado

- **Risco.** Um visitante ou fornecedor chamar rota de outro papel; um fornecedor ler ou apagar recurso de outro (IDOR); usar uma chave de API como credencial; ler arquivos por caminho forjado.
- **Mitigação.** Negação por padrão em `identity/SecurityConfig.java` (cadeia da API: só os `GET` públicos listados em `RateLimitFilter.PUBLIC_READS` passam sem papel; o resto é `denyAll`). Papéis por rota na mesma classe. Recursos do fornecedor são buscados pelo dono do token (`CurrentSubscriber`, `ManageApiKeysUseCase`), então o de outro dono é 404. Tópico de DLQ conferido contra lista fixa em `ops/application/ReprocessDlqUseCase.java`. A chave de API só escolhe o balde do limite e é ignorada nas rotas com papel (ADR-0019).
- **Testes.**
  - Matriz de rotas por papel: `identity/AuthorizationMatrixIT` (gera `authz-matrix.md` e confere que cobre todo o contrato).
  - Chave de API não autentica: `identity/SecurityConfigTest.anApiKeyIsNotACredentialOnAnyProtectedRoute` e `identity/adapter/in/web/RateLimitFlowIT.anApiKeyNeverAuthenticatesASupplierOrOperatorRoute`.
  - IDOR em busca salva (ler e apagar a de outro assinante é 404): `alerts/adapter/AlertsFlowIT.anotherSubscribersSearchIsAlways404`, `alerts/adapter/in/web/SavedSearchControllerTest.getOfAnotherSubscribersSearchIs404Problem` e `deleteOfAnotherSubscribersSearchIs404`.
  - IDOR em chave de API (revogar a de outro é 404): `identity/adapter/in/web/ApiKeyLifecycleIT.anotherSubjectsPrefixAndARevokedKeyAreNotFound`.
  - Tópico de DLQ fora da lista: `ops/adapter/in/web/DlqControllerTest.anUnknownTopicOrIdIsAProblemNotFound`.
  - CSRF nas páginas com sessão: `identity/SecurityConfigTest.pagesPostOnlyWithTheCookieTokenEchoedInAHeader`.
  - Console de flags do Togglz (`/ops/togglz`, só `operator`; visitante vai ao login, `supplier` recebe 403, POST sem o token CSRF da sessão ou sem o do console não muda nada): `ops/adapter/config/TogglzConsoleIT` (`aVisitorIsSentToTheLogin`, `aSupplierIsForbidden`, `aPostWithoutTheSessionCsrfTokenIsRejectedBeforeTheConsole`, `aPostWithoutTheTokenOfTheConsoleChangesNothing`).
  - Caminho forjado em cursor, id, prefixo e arquivos estáticos: `search/adapter/in/rest/TraversalAttackIT` (cursor com NUL, valores absurdos ou que não é base64: 400; id com `..`, `%2e%2e`, `%2f`, NUL, `\`: 400 ou 404; `/css/..`, `/js/%2e%2e/`, `/application.properties`, migrações e templates: 400 ou 404; mesma lista nas rotas `/licitacoes/{id}`, `/api/v1/api-keys/{prefix}` e `/api/v1/saved-searches/{id}`).
  - Defeito achado por esse teste e corrigido: `DELETE /api/v1/api-keys/{prefix}` com prefixo de tamanho errado respondia 500 (violação de validação sem tratamento); agora é 404 `not-found` (`identity/adapter/in/web/ApiKeysControllerTest.aPrefixOfTheWrongSizeNamesNoKeyAndIsA404`).
- **Observação.** O id dentro de um cursor é só uma chave de ordenação, ligada como valor: um id com `../` não é arquivo nem erro, só não casa com nada (`TraversalAttackIT.aPathLikeCursorIdIsOnlyAKeyAndReadsNoFile`).
- **Pendente.** Nenhuma restrição de acesso por objeto além de busca salva e chave de API existe hoje (não há outro recurso por dono); revisar quando houver.

## A02 Falhas criptográficas

- **Risco.** Segredo guardado em claro; tráfego sem TLS; token de verificação de e-mail legível no banco.
- **Mitigação.** Chaves de API guardadas só como SHA-256 mais o prefixo (`identity/domain/ApiKey.java`, migração V12); o segredo aparece uma vez, na criação. O token de verificação de e-mail também é guardado como hash (`alerts/application/VerifySubscriberUseCase.java`, `Sha256`). Senhas e login ficam no Keycloak, a aplicação não guarda senha. HSTS de um ano em requisição HTTPS (`SecurityConfig`). Segredos por ambiente (`RADAR_WEB_CLIENT_SECRET`, `RADAR_READ_WRITER_PASSWORD`), conferidos no histórico pelo Gitleaks (`docs/security/scans.md`).
- **Testes.** `identity/domain/ApiKeyTest.onlyTheHashIsKeptNeverTheSecret`, `identity/adapter/out/jdbc/JdbcApiKeysIT.onlyTheHashAndThePrefixAreStored`, `identity/adapter/in/web/ApiKeyLifecycleIT.theKeyIsShownOnceAndOnlyItsHashIsStored`, `identity/SecurityConfigTest.everyResponseCarriesTheSecurityHeaders` (HSTS só em HTTPS).
- **Pendente.** TLS: a aplicação fala HTTP e o TLS é do proxy ou ingress do deploy; não há teste de TLS neste repositório. As conexões com Postgres, Redis e Kafka são sem TLS no compose de desenvolvimento; ligar TLS é decisão do ambiente de produção, ainda não documentada.

## A03 Injeção

- **Risco.** Texto da busca virar SQL; operadores de `tsquery` ou curingas (`%`, `_`, `\`) quebrarem a consulta; marcação HTML ou script do órgão licitante ou do visitante rodar na página; quebra de linha no assunto do e-mail.
- **Mitigação.** jOOQ com parâmetros ligados em todas as leituras (`search/adapter/out/jooq/JooqSearchReadModel.java`, `JooqOrganReadModel.java`): o texto é compilado por `websearch_to_tsquery` como valor, nunca concatenado. Caracteres de controle e texto acima de 200 (100 nos órgãos) são 400 (`search/adapter/in/rest/Texts.java`, contrato, `search/adapter/in/web/SearchPages.java`). Thymeleaf sem `th:utext`: o snippet é cortado nos marcadores `<b>` e cada pedaço sai escapado (`search/adapter/in/web/Snippets.java`). O assunto do e-mail perde quebras de linha (`alerts/adapter/out/mail/MailTemplates.java`, `oneLine`). Não há `Runtime.exec`, `ProcessBuilder` nem desserialização Java no código de produção.
- **Testes.**
  - Cargas de SQL, curingas, operadores de `tsquery`, Unicode, controle e texto longo em `q` de `/api/v1/procurements`, `/api/v1/organs`, `/` e `/fragments/results`, mais os demais filtros: `search/adapter/in/rest/InjectionAttackIT`. Respostas 200 ou 400, nunca 500; a tabela continua lá e com a mesma contagem de linhas.
  - Marcação do órgão e do visitante escapada em lista, fragmento, detalhe e no endereço que o htmx empurra: `search/adapter/in/web/HtmlEscapingIT`.
  - Nenhum modelo usa `th:utext` nem `[(...)]`: `search/adapter/in/web/TemplateEscapingTest`.
  - Assunto do e-mail sem CR/LF: `alerts/adapter/out/mail/MailTemplatesTest` (assunto com `\r\nBcc:`).
- **Pendente.** Regra estática contra concatenação de SQL: o ArchUnit não enxerga a concatenação de texto (o compilador a transforma em `invokedynamic`) e uma busca no código-fonte dá falso positivo com os `StringBuilder` de filtros fixos, então a garantia fica no comportamento (as cargas acima quebrariam a consulta se fossem concatenadas) e na revisão. As consultas de escrita que recebem evento do Kafka (`JooqProjectionStore`, adaptadores JDBC) usam parâmetros ligados; não há teste de carga hostil nelas porque a entrada vem do próprio sistema.

## A04 Design inseguro

- **Risco.** Abuso sem limite (raspagem, força bruta de chave, consulta pesada, acúmulo de dados por conta); cache envenenado.
- **Mitigação.** Limite de requisições por chave ou endereço em Redis, que abre se o Redis cair (`identity/adapter/in/web/RateLimitFilter.java`, ADR-0027). Chave inválida gasta ficha do endereço. No máximo 5 chaves ativas por fornecedor e 20 buscas salvas por assinante. Página de no máximo 50 itens, texto de até 200 caracteres, consulta cancelada em 3 s (`radar.search.statement-timeout`). Consentimento explícito antes de guardar e-mail e exclusão por `DELETE /api/v1/me` (ADR-0016). Idempotência na criação de busca salva. Cache de detalhe em Redis (`search/adapter/out/redis/RedisDetailCache.java`): a chave é `search:detail:v1:<id>`, só o que o banco devolveu é gravado (licitação que não existe não vira chave), o valor é dado público da licitação e nunca segredo, credencial ou resposta derivada da requisição (fase e ETag são recalculados pelo controlador), e uma falha do Redis abre para o banco com TTL em toda chave. A entrada do cliente nunca escolhe o valor guardado.
- **Testes.** `identity/adapter/in/web/RateLimitFlowIT` (limite anônimo, por chave, `invalidKeysSpendTheAddressBucketSoGuessingIsRateLimitedToo`, `aMalformedKeyIsA401EvenBeforeTheDatabaseIsAsked`), `identity/adapter/in/web/ApiKeyLifecycleIT.aSixthActiveKeyIsRefusedAndRevokingFreesASlot`, `alerts/adapter/AlertsFlowIT.twentyFirstActiveSearchIsRejected`, `search/adapter/out/jooq/SearchReadTimeoutIT.aSlowReadIsCancelledAndReportedAsATimeout`, `InjectionAttackIT` (texto acima do limite é 400), `search/adapter/out/redis/RedisDetailCacheTest.aProcurementThatIsNotProjectedIsNotCached` e `aMissReadsTheDatabaseAndStoresTheDetailUnderTheVersionedKeyWithTheTtl`, `RedisDetailCacheTest.aValueThatDoesNotParseIsReplacedByTheDatabaseRow`.
- **Pendente.** Não há modelagem de ameaças formal nem teste de carga hostil contra o limite além do fluxo acima.

## A05 Configuração incorreta de segurança

- **Risco.** Endpoint de gerenciamento exposto (`env`, `heapdump`), detalhe de saúde público, página de erro com pilha, cabeçalhos ausentes, CORS aberto, arquivos do classpath servidos.
- **Mitigação.** `management.endpoints.web.exposure.include=health,info,prometheus` (`core/src/main/resources/application.properties`): `health` e `info` públicos, sem detalhe de componentes; o resto do actuator (inclusive `prometheus`) exige o papel `operator`. Erros do Boot sem mensagem, exceção ou pilha (padrão do `spring.web.error.*` mantido); a busca e as páginas devolvem problema ou página fixa (`SearchProblemAdvice`, `WebAdvice`, `templates/error.html`). CSP sem `unsafe-inline`, `nosniff`, `Referrer-Policy`, `X-Frame-Options: DENY` e `frame-ancestors 'none'` em toda resposta (`SecurityConfig.headers`). Nenhuma configuração CORS, então nenhuma origem externa lê a API do navegador. Os recursos públicos são só os de `src/main/resources/static/`: o Spring MVC também consulta as pastas padrão do framework e a raiz do contêiner (`META-INF/resources`, `resources`, `public`), e isso não se desliga por propriedade, então a defesa é não ter nada nessas pastas.
- **Testes.**
  - Actuator, saúde, corpo de erro, cabeçalhos em página, fragmento, API, 401, 404 e estático, e ausência de CORS: `identity/MisconfigurationIT`.
  - Cabeçalhos e CSP: `identity/SecurityConfigTest.everyResponseCarriesTheSecurityHeaders`, `search/adapter/in/web/PageHygieneTest.everyPageCarriesThePolicyTheTemplatesAreWrittenFor`.
  - Falha inesperada sem vazar a causa: `search/adapter/in/rest/ProcurementsControllerTest.anUnexpectedFailureIsAFixedProblemAndLoggedOnce`, `search/adapter/in/web/WebErrorsTest.anUnexpectedFailureDoesNotLeakItsCause`.
  - Arquivos do classpath não servidos: `TraversalAttackIT.classpathFilesAreNotServed`. Nada fora de `static/` entre os recursos: `search/adapter/in/web/PublicLocationsTest`.
- **Pendente.** Os padrões de desenvolvimento do realm Keycloak (`infra/keycloak/realm-radar.json`) e do compose têm segredo e senha fixos, por desenho só para o ambiente local; não há teste que impeça levá-los a produção, só a regra de ADR-0019 de criar o realm com segredos próprios. O estado `FAILED` de `GET /ops/replay/last` mostra a mensagem da exceção, risco aceito porque só o `operator` lê.

## A06 Componentes vulneráveis e desatualizados

- **Risco.** Dependência ou imagem com falha conhecida.
- **Mitigação.** SBOM CycloneDX no `package`; Grype sobre o SBOM falha em CRITICAL; Trivy na imagem falha em CRITICAL ou HIGH com correção; versões e imagens fixadas por digest no `Makefile`; Dependabot semanal para Maven, Docker, compose e GitHub Actions (`.github/dependabot.yml`); CodeQL. Detalhes e como adicionar ignore justificado em `docs/security/scans.md`. O `lz4-java` está fixado em 1.11.4 no `pom.xml` por um aviso HIGH.
- **Teste.** Não é teste Java: o portão é o job `supply-chain` e o job `image` do `ci.yml` (`make sbom-scan`, `make image-scan`); o resultado de 2026-10-22 está em `scans.md`.
- **Pendente.** O portão só falha em CRITICAL no SBOM; HIGH sem correção disponível fica visível no relatório, sem bloquear.

## A07 Falhas de identificação e autenticação

- **Risco.** Token de outro emissor ou de outro cliente aceito; adivinhação de chave de API; sessão fraca.
- **Mitigação.** Keycloak, sem login próprio. Token JWT com assinatura do JWKS, emissor `radar.oidc.issuer` e audiência `radar-api`; ID token também com validação de emissor (`SecurityConfig.idTokenDecoders`). Chave de API: forma fixa verificada antes de consultar o banco, busca pelo hash SHA-256 de um segredo de 256 bits (a comparação é a igualdade do índice sobre o hash, não do segredo, então o tempo de resposta não revela o segredo), chave inválida gasta ficha do balde do endereço. Autocadastro desligado e `admin-cli` sem concessão por senha no realm. Se as chaves do Keycloak estão inalcançáveis, o token não pôde ser julgado: a resposta é 503 `unavailable` com `Retry-After: 5` e detalhe fixo, nunca 500 nem 401 (`identity/adapter/in/web/KeysUnavailableFilter.java`). Sessão do navegador com CSRF e troca do identificador no login (padrão do Spring Security).
- **Testes.** `identity/SecurityConfigTest.idTokenFromAnotherIssuerIsRejected`, `identity/KeycloakIssuerMismatchIT.validTokenFromAnUnexpectedIssuerIs401`, `identity/KeycloakIT.tamperedTokenIs401` e `tokenOfAnotherClientOfTheSameRealmLacksTheAudienceAndIs401`, `identity/domain/ApiKeyTest.anythingOtherThanTheIssuedShapeIsNotAKey`, `RateLimitFlowIT.aMalformedKeyIsA401EvenBeforeTheDatabaseIsAsked` e `invalidKeysSpendTheAddressBucketSoGuessingIsRateLimitedToo`, `identity/SecurityConfigTest.aTokenThatCannotBeCheckedBecauseTheKeysAreUnreachableIs503WithoutDetail`.
- **Pendente.** MFA, WebAuthn e logout federado estão fora do escopo (ADR-0019). Não há teste de fixação de sessão (comportamento padrão do framework, sem configuração própria).

## A08 Falhas de integridade de software e dados

- **Risco.** Script de terceiro adulterado; dependência trocada; desserialização insegura; evento malformado.
- **Mitigação.** O htmx é servido pela própria aplicação (`static/js/htmx-2.0.11.min.js`) com SHA-256 registrado no layout; a CSP só aceita `script-src 'self'`, sem CDN. Versões fixadas (BOM do Boot, `pom.xml`, imagens por digest no `Makefile`). Eventos do Kafka em JSON ou Avro com esquema (`EnvelopeDeserializer`), sem desserialização Java. Grype e Gitleaks como em A06.
- **Testes.** `search/adapter/in/web/PageHygieneTest.htmxAndTheStylesheetAreServedByTheApplicationItself` e `theLayoutNamesTheFileItLoadsAndItsChecksum` (SHA do htmx), `PageHygieneTest.noPageHasInlineScriptStyleOrEventHandlers`, `identity/SecurityConfigTest.everyResponseCarriesTheSecurityHeaders` (CSP).
- **Ações do GitHub.** Todas as ações de `ci.yml`, `main.yml` e `codeql.yml` estão fixadas por SHA de commit, com a versão em comentário; o Dependabot as atualiza.
- **Pendente.** Não há assinatura de imagem nem verificação de procedência.

## A09 Falhas de registro e monitoramento

- **Risco.** Segredo no log; ataque sem rastro.
- **Mitigação.** O segredo da chave de API nunca é impresso (`ApiKey.Issued.toString` mascara; o filtro do limite não registra cabeçalhos). Chaves do Keycloak inalcançáveis geram um WARN por minuto (o resto em DEBUG, `KeysUnavailableFilterTest`). Falhas inesperadas são registradas uma vez no servidor e saem como problema fixo para o cliente. Métricas do limite (`MicrometerRateLimitMetrics`) e do cache em `/actuator/prometheus` (só `operator`), com alertas em `infra/prometheus`.
- **Testes.** `identity/adapter/in/web/SecretsInLogsIT` (chave válida, desconhecida e malformada em `X-Api-Key` e tokens em `Authorization` não aparecem no log, com os registradores da aplicação em DEBUG), `identity/domain/ApiKeyTest.theSecretNeverShowsInToString`, `ProcurementsControllerTest.anUnexpectedFailureIsAFixedProblemAndLoggedOnce`.
- **Pendente.** Não há log de auditoria dedicado a eventos de segurança (negações 401 e 403 contam em `http_server_requests`, mas sem alerta próprio); falhas de login ficam no Keycloak. Não há teste de neutralização de quebra de linha em valores registrados (nenhuma entrada de requisição é registrada hoje, o DLQ só registra tópico já validado).

## A10 Falsificação de requisição do lado do servidor (SSRF)

- **Risco.** O servidor buscar uma URL escolhida pelo chamador.
- **Mitigação.** Nenhum recurso busca URL vinda de requisição. A única chamada HTTP de saída do código da aplicação é o `PncpHttpGateway`, com `radar.pncp.base-url` da configuração e caminho fixo; a consulta recebe datas e números, então nenhum argumento carrega endereço, e o cliente do JDK não segue redirecionamento (padrão `NEVER`). O JWKS do Keycloak e o registro de esquemas vêm da configuração e são lidos por bibliotecas. E-mail sai por SMTP configurado, sem busca de conteúdo remoto. Não há webhook nem importação por URL.
- **Testes.** Regra de arquitetura `architecture/ArchitectureTest.only_the_pncp_gateway_calls_out`: nenhuma classe fora de `ingest.adapter.out.pncp` depende dos pacotes `java.net.http`, `org.springframework.web.client`, `org.springframework.web.reactive.function.client` (WebClient), `org.apache.hc` ou `okhttp3`, nem de subtipos de `URLConnection`, nem chama `URL.openConnection`, `openStream` ou `getContent` (qualquer sobrecarga). Tipos de exceção desses pacotes (por exemplo `HttpTimeoutException`, usado só para classificar falhas) não contam como cliente. A prova de que a regra enxerga cada violação está em `architecture/ArchitectureRulesSeeViolationsTest.outboundRuleFlagsEveryWayToOpenAConnectionOutsideThePncpGateway`, com fixtures para o cliente do JDK, `RestClient`, `RestTemplate`, `HttpURLConnection`, `URLConnection` e as três chamadas de `URL`; o WebClient só é coberto pelo nome do pacote, sem fixture, porque a biblioteca reativa não está no classpath. Também `ingest/adapter/out/pncp/PncpGatewayHostTest` (um 302 para outro host não é seguido; toda chamada vai ao host configurado, no caminho fixo).
- **Pendente.** Bibliotecas que buscam endereços da configuração (JWKS, registry) não são cobertas pela regra, pois não são código da aplicação; a configuração vem do ambiente, não de requisição.
