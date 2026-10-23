# ADR-0019: segurança: Keycloak OIDC, três papéis, duas cadeias de filtros

- Status: aceita
- Data: 2026-10-22

## Contexto

A API pública de busca é aberta, mas buscas salvas, exclusão de dados (LGPD) e chaves de API pertencem a um fornecedor, e as rotas `/ops` e o actuator são de operação. O mesmo processo serve a API REST (clientes com token) e páginas HTML (navegador com sessão). Não queremos guardar senha nem implementar login próprio.

## Decisão

**Provedor de identidade.** Keycloak 26, realm `radar` versionado em `infra/keycloak/realm-radar.json`:
- cliente `radar-web`, confidencial, só fluxo de autorização (código), redirect `http://localhost:8080/login/oauth2/code/keycloak`, com um mapper que põe `radar-api` na audiência do access token e outro que põe os papéis do realm no ID token;
- cliente `radar-api`, só portador (bearer-only), que existe para ser a audiência exigida pela API;
- papéis do realm `supplier` e `operator`; usuários de desenvolvimento `fornecedor` e `operador`, com senhas padrão documentadas em `infra/compose/.env.example`;
- cliente `radar-ops-cli`, confidencial, só client credentials (conta de serviço com o papel `operator` e o mapper de audiência `radar-api`), para a automação de operação: `make seed` e o `e2e` chamam `/ops/backfill` com ele, sem senha de usuário (segredo de desenvolvimento, só neste arquivo de realm);
- o cliente embutido `admin-cli` vem com concessão direta (senha) desligada, para que nenhum cliente do realm troque senha por token.

O arquivo do realm serve só ao ambiente local: segredo do cliente e senhas são padrões de desenvolvimento. Fora dele o realm é criado com segredos próprios.

**Três papéis.** Visitante é o anônimo: lê as rotas `GET` públicas da API (busca, detalhe, histórico, estatísticas, órgãos), `actuator/health` e `actuator/info`, e as páginas públicas. `supplier` usa `/api/v1/saved-searches/**`, `/api/v1/me`, `/api/v1/api-keys` e as páginas `/assinatura` e `/buscas/**`. `operator` usa `/ops/**` e o restante do actuator. Os papéis vêm da claim `realm_access.roles` e viram autoridades `ROLE_<papel>`.

**Duas cadeias** (`identity/SecurityConfig`):
1. `/api/**`, `/ops/**` e `/actuator/**`: servidor de recursos JWT, sem sessão e sem CSRF (não há cookie de autenticação que um site terceiro possa usar). O token precisa de assinatura válida (JWKS do realm), emissor `radar.oidc.issuer` e audiência `radar-api`: um token de outro cliente do mesmo realm (sem a audiência) recebe 401. Negação por padrão: sob `/api` só são públicos os `GET` de `/api/v1/procurements/**`, `/api/v1/stats` e `/api/v1/organs` (as rotas que o contrato marca com `x-required-role: none`); qualquer outra rota, inclusive `GET`, é negada se nenhuma regra de papel casar. `AuthorizationMatrixIT` confere que a matriz cobre toda operação do contrato com o papel de `x-required-role`. 401 e 403 saem como `application/problem+json` (tipos `unauthorized` e `forbidden`), com `WWW-Authenticate: Bearer` no 401.
2. Páginas: login OIDC (`/oauth2/authorization/keycloak`), sessão HTTP e CSRF ligado. O ID token é validado contra o JWKS do realm e também contra o emissor `radar.oidc.issuer` (`OidcIdTokenDecoderFactory` com `JwtIssuerValidator`, montado no primeiro login). O token CSRF vai num cookie `XSRF-TOKEN` legível por script e volta no cabeçalho `X-XSRF-TOKEN` (ou no campo oculto `_csrf`, que a página `/assinatura` traz preenchido para funcionar sem scripts). Sem sessão, a página redireciona para o login; sem o papel, responde 403. Uma página com htmx manda o mesmo cabeçalho, lido do cookie num script externo (`htmx:configRequest`). Formulários das páginas que gravam dados (`POST /buscas`) passam por esta cadeia, porque o navegador não tem token Bearer.

**Cabeçalhos em toda resposta:** CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` (sem `unsafe-inline`: scripts e estilos das páginas são arquivos estáticos), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY` e HSTS de um ano só em requisições HTTPS.

**Consentimento.** Autenticar não cria assinante. A conta aceita receber alertas em `/assinatura` (com sessão e CSRF). O e-mail precisa estar verificado no provedor (`email_verified` verdadeiro no ID token); senão a resposta é 403 com o problema `email-not-verified`. O assinante é criado com o e-mail do ID token e fica ligado ao `sub` (`alerts.subscriber.external_subject`, único, migração V10). A confirmação do e-mail por link continua (ADR-0016). Um cadastro pendente só é reaproveitado pela própria conta (ou, sem conta ligada, por qualquer uma); se outra conta o detém, a resposta é 409 (`conflict`, "e-mail already registered"), sem tomar a linha. Uma conta que já tem assinante e tenta outro e-mail recebe 409 com a mensagem "this account already has a subscriber". Enquanto não há assinante para o `sub`, as rotas de buscas salvas respondem 403 com o problema `consent-required`, que aponta para `/assinatura`; `DELETE /api/v1/me` responde 204, porque não há dado a apagar. A exclusão desfaz o vínculo com a conta, que pode consentir de novo como assinante novo.

**Segredos por ambiente.** `RADAR_OIDC_ISSUER` e `RADAR_WEB_CLIENT_SECRET` vêm do ambiente; o repositório só tem os valores padrão de desenvolvimento do realm. Os endpoints do Keycloak são configurados explicitamente (não por descoberta), então a aplicação sobe sem o Keycloak no ar e só busca as chaves na primeira validação.

**Chaves de API** (PR 5.2). O `supplier` cria até 5 chaves ativas em `POST /api/v1/api-keys` (a sexta é 422 `validation`) e revoga por prefixo em `DELETE /api/v1/api-keys/{prefix}` (chave de outro usuário, inexistente ou já revogada é 404). A chave tem a forma `rk_<prefixo de 8 hex>_<32 bytes aleatórios em base64url>`, aparece só na resposta da criação e o banco guarda apenas o SHA-256 (`identity.api_key.key_hash`) e o prefixo (migração V12). Um segredo de 256 bits aleatórios dispensa sal e hash lento. Só a camada `free` é emitida pela API; `pro` existe na configuração e é concedida à mão no banco. A chave vale só nas rotas `GET` públicas, onde escolhe o balde do limite de requisições (ADR-0027): ela não autentica nada, e em qualquer rota com papel o cabeçalho `X-Api-Key` é ignorado (sem Bearer, 401). Um `X-Api-Key` que não tenha a forma emitida, ou que seja desconhecido ou revogado, numa rota pública é 401 `unauthorized` com `WWW-Authenticate: ApiKey`, e gasta uma ficha do balde do endereço (ADR-0027). O prefixo é único por dono (`UNIQUE (owner_subject, prefix)`); numa colisão, a criação sorteia de novo. A resposta da criação traz a chave em claro: não deixe ligado em produção o log DEBUG de corpos de requisição e resposta.

**LGPD e chaves.** A tabela `identity.api_key` guarda o hash, o prefixo, a camada e o `sub` do Keycloak (identificador opaco, sem nome nem e-mail). Não há chave estrangeira para `alerts.subscriber`: o contexto `identity` não depende de `alerts`. `DELETE /api/v1/me` apaga o assinante e não toca nas chaves, que continuam revogáveis pelo dono; o que ficou é um identificador opaco e um hash, sem dado pessoal de contato.

## Alternativas rejeitadas (e por quê)

- Login próprio com senha no banco: guardaria segredo de usuário e exigiria recuperação de senha, bloqueio e auditoria que o Keycloak já tem.
- Uma cadeia só com sessão para API e páginas: a API teria CSRF e cookie, o que atrapalha clientes de linha de comando e mistura dois modelos de ameaça.
- Descoberta OIDC (`issuer-uri`) no cliente web: a aplicação não subiria com o Keycloak fora do ar e todo teste de contexto precisaria dele.
- Criar o assinante no primeiro acesso autenticado: guardaria e-mail sem consentimento explícito, contra a ADR-0016.

## Consequências

- Fora do escopo: MFA, WebAuthn, autocadastro de usuários (desligado no realm), logout federado. O logout encerra a sessão da aplicação, mas não a do Keycloak; no mesmo navegador, o próximo login entra sem pedir senha. Sem descoberta OIDC o cliente web não conhece o `end_session_endpoint`.
- Cache: CSS e JS das páginas saem com `Cache-Control: max-age=3600` (`spring.web.resources.cache.cachecontrol.max-age`). Nas demais respostas o Spring Security grava `no-cache, no-store` quando o handler não define nada. Os controladores de busca precisam declarar o próprio `Cache-Control` (por exemplo `no-cache`) junto com o `ETag`, senão o `no-store` impede a revalidação; o que o controlador define é mantido.
- O scrape do Prometheus em `/actuator/prometheus` exige token com o papel `operator` (ou, no deploy, porta de gerenciamento separada fora da rede pública).
- Toda rota nova precisa entrar nas regras de `SecurityConfig` e em `AuthorizationMatrixIT`; rota de API não listada e não `GET` é negada por padrão.

## Prova

- `docs/security/authz-matrix.md`, gerada por `core/src/test/java/br/dev/miguelsanzbr/radar/identity/AuthorizationMatrixIT.java` com o resultado real de cada rota para visitante, `supplier` e `operator`.
- `core/src/test/java/br/dev/miguelsanzbr/radar/identity/KeycloakIT.java`: token real do Keycloak (Testcontainers) do `fornecedor` grava busca salva (201); o do `operador` recebe 403; token adulterado ou emitido para `admin-cli` (sem a audiência) recebe 401. `KeycloakIssuerMismatchIT`: token válido com emissor diferente do configurado recebe 401.
- `core/src/test/java/br/dev/miguelsanzbr/radar/identity/SecurityConfigTest.java`: papéis do token, 401/403 em problem+json, CSRF nas páginas e não na API, cabeçalhos de segurança.
