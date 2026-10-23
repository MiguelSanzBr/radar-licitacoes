# Matriz de autorização

Arquivo gerado por `core/src/test/java/br/dev/miguelsanzbr/radar/identity/AuthorizationMatrixIT.java`, que chama cada rota como cada tipo de usuário e registra o que a aplicação respondeu. Não edite à mão: rode o teste e faça commit do arquivo regravado. O teste falha se o arquivo versionado for diferente do gerado.

- `permitido`: a requisição passou pela segurança (a resposta pode ser 2xx, 4xx de negócio ou 404 de rota ainda não implementada).
- `401`: sem credencial válida; resposta `application/problem+json` do tipo `unauthorized`.
- `403`: papel insuficiente; nas rotas de API, problema do tipo `forbidden`.
- `login`: página exige sessão; o navegador é redirecionado para o login OIDC.

As rotas de API recebem um JWT (Bearer); as páginas, a sessão criada pelo login OIDC e o token CSRF. O `supplier` da matriz já deu consentimento (`/assinatura`).

| Rota | Canal | Visitante | supplier | operator |
|---|---|---|---|---|
| `GET /api/v1/procurements` | API | permitido | permitido | permitido |
| `GET /api/v1/procurements/{id}` | API | permitido | permitido | permitido |
| `GET /api/v1/procurements/{id}/history` | API | permitido | permitido | permitido |
| `GET /api/v1/stats` | API | permitido | permitido | permitido |
| `GET /api/v1/organs` | API | permitido | permitido | permitido |
| `POST /api/v1/saved-searches` | API | 401 | permitido | 403 |
| `GET /api/v1/saved-searches/{id}` | API | 401 | permitido | 403 |
| `DELETE /api/v1/saved-searches/{id}` | API | 401 | permitido | 403 |
| `DELETE /api/v1/me` | API | 401 | permitido | 403 |
| `POST /api/v1/api-keys` | API | 401 | permitido | 403 |
| `DELETE /api/v1/api-keys/{prefix}` | API | 401 | permitido | 403 |
| `GET /ops/health-panel` | API | 401 | 403 | permitido |
| `POST /ops/replay` | API | 401 | 403 | permitido |
| `POST /ops/dlq/{topic}/reprocess` | API | 401 | 403 | permitido |
| `POST /ops/backfill` | API | 401 | 403 | permitido |
| `GET /actuator/health` | API | permitido | permitido | permitido |
| `GET /actuator/info` | API | permitido | permitido | permitido |
| `GET /actuator/metrics` | API | 401 | 403 | permitido |
| `GET /actuator/prometheus` | API | 401 | 403 | permitido |
| `GET /privacidade` | página | permitido | permitido | permitido |
| `GET /assinatura/confirmar` | página | permitido | permitido | permitido |
| `GET /assinatura` | página | login | permitido | 403 |
| `POST /assinatura` | página | login | permitido | 403 |
| `GET /buscas/nova` | página | login | permitido | 403 |
| `POST /buscas` | página | login | permitido | 403 |
