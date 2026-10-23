# Configuração 12-factor

Regra (fator III do 12-factor): tudo que varia por ambiente vem do ambiente, e nenhum segredo vive no repositório. As flags de operação (Togglz) e o ADR-0021 chegam no PR 4.3 e vão apontar para este arquivo.

## Como a configuração chega

- Valor que depende do ambiente (URLs, hosts, portas, credenciais, limites de recurso) vem de variável de ambiente ou de arquivo de segredo montado (`spring.config.import=optional:configtree:/run/secrets/`). A ligação relaxada do Boot faz de `radar.pncp.base-url` a variável `RADAR_PNCP_BASE_URL`, sem código.
- `application.properties` guarda só o que é igual em todo ambiente (nomes de tópicos, política de retentativas, buckets de métricas) e, no máximo, padrões de desenvolvimento listados abaixo. Não há `application.yml`, nem arquivo por ambiente.
- Segredos: nunca no repositório, na imagem ou nos values do Helm. No compose vêm de `infra/compose/secrets/` (gerado por `scripts/compose-init.sh`, fora do git); no Kubernetes, de um Secret (`secret.existingName`).
- O gitleaks (`make secrets-scan`) varre o histórico e a árvore; um padrão de desenvolvimento só passa por allowlist com regex exata e motivo (`docs/security/scans.md`).
- A imagem roda com o perfil `json` (`application-json.properties`). Nele a senha do papel de leitura não tem padrão: sem `RADAR_READ_WRITER_PASSWORD` (ou o arquivo de segredo equivalente do compose) a aplicação não sobe. O padrão `radar_rw_dev` só vale fora da imagem (testes e execução local pelo Maven).

## Inventário

Auditoria de `core/src/main/resources/application*.properties`, de `infra/compose/docker-compose.yml` e do chart `infra/helm/radar` em 2026-10-22. Revise esta tabela em todo PR que adicionar uma propriedade `radar.*`.

| Valor | Variável de ambiente | Padrão no repositório | Segredo? |
|---|---|---|---|
| URL, usuário e senha do banco | `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | nenhum | senha: sim (compose: arquivo `db_password`; Helm: Secret) |
| Senha do papel `radar_read_writer` (Flyway V6 e fonte de dados da busca) | `RADAR_READ_WRITER_PASSWORD` | `radar_rw_dev` fora da imagem; nenhum no perfil `json` | sim |
| URL e usuário da fonte de dados da busca | `RADAR_SEARCH_DATASOURCE_URL`, `_USERNAME` | vazio (mesmo banco) e `radar_read_writer` | não |
| Kafka | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` (padrão do Boot) | não |
| Redis (limite de requisições) | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | `localhost`, `6379` | não |
| Limite de requisições e chaves de API | `RADAR_RATE_LIMIT_ENABLED`, `RADAR_RATE_LIMIT_FREE_PER_MINUTE`, `_PRO_PER_MINUTE`, `_ANONYMOUS_PER_MINUTE`, `RADAR_API_KEYS_MAX_PER_SUBJECT`, `RADAR_HTTP_TRUST_FORWARDED` | ligado, 60, 600, 30 por minuto, 5 chaves, `false` | não |
| SMTP | `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | nenhum | não |
| Emissor OIDC | `RADAR_OIDC_ISSUER` | `http://localhost:8180/realms/radar` | não |
| Segredo do cliente `radar-web` | `RADAR_WEB_CLIENT_SECRET` | vazio | sim |
| Endpoints do Keycloak por nome de serviço | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` e as do provedor `keycloak` | derivados do emissor | não |
| PNCP | `RADAR_PNCP_BASE_URL`, `RADAR_PNCP_REQUESTS_PER_SECOND` | `https://pncp.gov.br`, 1 req/s | não |
| Telemetria | `OTEL_EXPORTER_OTLP_ENDPOINT`, `MANAGEMENT_OTLP_METRICS_EXPORT_*`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | `http://localhost:4318`, amostragem 1.0 | não |
| Coleta, partições, alertas, outbox, projetor | `RADAR_INGEST_*`, `RADAR_ALERTS_*`, `RADAR_OUTBOX_*`, `RADAR_SEARCH_PROJECTOR_*` | padrões das ADRs 0006, 0015 e 0018 | não |

## Achados

- Nenhum segredo de produção em arquivo versionado. Os únicos valores secretos no repositório são padrões de desenvolvimento: `radar_rw_dev` (senha do papel de leitura, só fora da imagem), `radar-web-dev-secret` e `radar-ops-dev-secret` (clientes do realm de desenvolvimento em `infra/keycloak/realm-radar.json`, repetidos em `infra/compose/.env.example` e usados por `scripts/ops-token.sh`) e `troque-me` (admin do Keycloak local).
- Corrigido nesta auditoria: a imagem subia com `radar_rw_dev` se ninguém definisse a senha do papel de leitura. O perfil `json` agora exige a variável, e o `values-smoke.yaml` do Helm passa um valor. O teste `JsonProfileSecretsTest` guarda o comportamento.
- `application-aot-train.properties` tem usuário e senha `aot` e uma URL de banco fictícia: é o perfil do treino do AOT cache na construção da imagem, sem Flyway e sem conexão, e nunca roda com dado real.
- Os limites do container (`mem_limit` do compose, `resources` do Helm) e os intervalos de agendamento são por ambiente e ficam no compose e nos values, não no código.
- O chart do Helm só declara no ConfigMap o URL do banco e o endereço do Kafka. Os demais valores acima chegam por `config` (não secretos) e `secret.values` ou `secret.existingName` (secretos). O `values.yaml` lista as chaves a definir.
