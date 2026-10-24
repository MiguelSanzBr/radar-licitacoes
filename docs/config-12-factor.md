# Configuração 12-factor

Regra (fator III do 12-factor): tudo que varia por ambiente vem do ambiente, e nenhum segredo vive no repositório. As flags de funcionalidade (Togglz) e a fronteira entre flag e configuração estão no [ADR-0021](adr/0021-feature-flags-togglz.md), que aponta para este arquivo.

## Como a configuração chega

- Valor que depende do ambiente (URLs, hosts, portas, credenciais, limites de recurso) vem de variável de ambiente ou de arquivo de segredo montado (`spring.config.import=optional:configtree:/run/secrets/`). A ligação relaxada do Boot faz de `radar.pncp.base-url` a variável `RADAR_PNCP_BASE_URL`, sem código.
- `application.properties` guarda só o que é igual em todo ambiente (nomes de tópicos, política de retentativas, buckets de métricas) e, no máximo, padrões de desenvolvimento listados abaixo. Não há `application.yml`, nem arquivo por ambiente.
- Segredos: nunca no repositório, na imagem ou nos values do Helm. No compose vêm de `infra/compose/secrets/` (gerado por `scripts/compose-init.sh`, fora do git); no Kubernetes, de um Secret (`secret.existingName`).
- O gitleaks (`make secrets-scan`) varre o histórico e a árvore; um padrão de desenvolvimento só passa por allowlist com regex exata e motivo (`docs/security/scans.md`).
- A imagem roda com o perfil `json` (`application-json.properties`). Nele a senha do papel de leitura não tem padrão: sem `RADAR_READ_WRITER_PASSWORD` (ou o arquivo de segredo equivalente do compose) a aplicação não sobe. O padrão `radar_rw_dev` só vale fora da imagem (testes e execução local pelo Maven).

## Inventário

Auditoria de `core/src/main/resources/application*.properties`, das sobreposições dos apps (`apps/*/src/main/resources`), de `infra/compose/docker-compose.yml`, do chart `infra/helm/radar` e do `user_data` do Terraform (`infra/terraform/modules/compute`) em 2026-10-24. Revise esta tabela em todo PR que adicionar uma propriedade `radar.*`.

| Valor | Variável de ambiente | Padrão no repositório | Segredo? |
|---|---|---|---|
| URL, usuário e senha do banco | `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | nenhum | senha: sim (compose: arquivo `db_password`; Helm: Secret) |
| Senha do papel `radar_read_writer` (Flyway V6 e fonte de dados da busca) | `RADAR_READ_WRITER_PASSWORD` | `radar_rw_dev` fora da imagem; nenhum no perfil `json` | sim |
| URL e usuário da fonte de dados da busca | `RADAR_SEARCH_DATASOURCE_URL`, `_USERNAME` | vazio (mesmo banco) e `radar_read_writer` | não |
| Conexões dos pools do Hikari: escrita (JPA, coleta, outbox, alertas) e busca (leituras públicas e projetor), medidos em `docs/benchmarks/db/hikari.md` (ADR-0010, "Tamanho do pool"); cada instância abre os dois, então some ao `max_connections` do Postgres | `RADAR_DB_POOL_SIZE`, `RADAR_SEARCH_POOL_SIZE` | `10` e `10` | não |
| Tempo máximo de uma consulta pública da busca | `RADAR_SEARCH_STATEMENT_TIMEOUT` | `3s` | não |
| Candidatos do ranking da busca textual: as N licitações mais recentes que casam são ranqueadas e paginadas (ADR-0011); maior N dá mais relevância e mais custo por busca | `RADAR_SEARCH_RANK_CANDIDATES` | `2000` (mínimo 1) | não |
| Kafka | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` (padrão do Boot) | não |
| Redis (limite de requisições e cache do detalhe) | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | `localhost`, `6379` | não |
| Limite de requisições e chaves de API | `RADAR_RATE_LIMIT_ENABLED`, `RADAR_RATE_LIMIT_FREE_PER_MINUTE`, `_PRO_PER_MINUTE`, `_ANONYMOUS_PER_MINUTE`, `RADAR_API_KEYS_MAX_PER_SUBJECT`, `RADAR_HTTP_TRUST_FORWARDED` | ligado, 60, 600, 30 por minuto, 5 chaves, `false` | não |
| Cache do detalhe no Redis (ADR-0027) | `RADAR_SEARCH_DETAIL_CACHE_ENABLED`, `RADAR_SEARCH_DETAIL_CACHE_TTL` | ligado, `1h` | não |
| SMTP | `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `localhost` e `1025` (o Mailpit do compose responde por `mailpit:1025`) | não |
| Remetente dos e-mails e endereço público usado nos links deles (edital, confirmação, gerenciar buscas) | `RADAR_MAIL_FROM`, `RADAR_PUBLIC_BASE_URL` | `radar@localhost`, `http://localhost:8080` | não |
| Emissor OIDC | `RADAR_OIDC_ISSUER` | `http://localhost:8180/realms/radar` | não |
| Montagem dos segredos como propriedades (configtree: o nome do arquivo é a propriedade, por exemplo `spring.datasource.password`) | `SPRING_CONFIG_IMPORT` | nenhum no código; o compose e o `user_data` do Terraform usam `optional:configtree:/run/secrets/`; no Helm os segredos chegam como variáveis do Secret, sem configtree | não |
| Perfis: a imagem inclui o `json` | `SPRING_PROFILES_INCLUDE` | `json` (fixado no `Dockerfile`) | não |
| Segredo do cliente `radar-web` | `RADAR_WEB_CLIENT_SECRET` | vazio | sim |
| Endpoints do Keycloak por nome de serviço | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` e as do provedor `keycloak` | derivados do emissor | não |
| PNCP | `RADAR_PNCP_BASE_URL`, `RADAR_PNCP_REQUESTS_PER_SECOND` | `https://pncp.gov.br`, 0,2 req/s | não |
| Telemetria | `OTEL_EXPORTER_OTLP_ENDPOINT`, `MANAGEMENT_OTLP_METRICS_EXPORT_*`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | `http://localhost:4318`, amostragem 1.0 | não |
| Formato dos eventos e registry de schemas (ADR-0024): `json` ou `avro`; o registry só é usado com `avro`; os prazos valem para cada chamada ao registry e para a pausa do consumidor depois de uma falha dele (0 desliga a pausa) | `RADAR_MESSAGING_FORMAT`, `RADAR_MESSAGING_REGISTRY_URL`, `RADAR_MESSAGING_REGISTRY_TIMEOUT_MS`, `RADAR_MESSAGING_REGISTRY_BACKOFF_MS` | `json`, `http://localhost:8081/apis/registry/v3`, 10 000 ms, 5 000 ms (o compose aponta o registry para `apicurio:8080`) | não |
| Quem publica o outbox (ADR-0018): `polling` (o relay do app) ou `cdc` (Debezium); nunca os dois | `RADAR_OUTBOX_MODE` | `polling` | não |
| Coleta, partições, alertas, outbox, projetor | `RADAR_INGEST_*`, `RADAR_ALERTS_*`, `RADAR_OUTBOX_*`, `RADAR_SEARCH_PROJECTOR_*` | padrões das ADRs 0006, 0015 e 0018 | não |
| Estado inicial das flags (Togglz) | `RADAR_INGEST_INCREMENTAL_ENABLED`, `RADAR_INGEST_CLOSE_EXPIRED_ENABLED`, `RADAR_INGEST_BACKFILL_SCHEDULED_ENABLED`, `RADAR_ALERTS_DIGEST_ENABLED` | ligadas só a coleta incremental, o fechamento e o resumo; `MAX_SAVED_SEARCHES_LIMIT` nasce ligada; `READ_VALUE_CENTS` nasce desligada (sem variável: só `/ops/togglz`) | não |
| Preenchimento dos centavos do valor estimado (ADR-0010) | `RADAR_INGEST_VALUE_CENTS_BACKFILL_ENABLED`, `RADAR_INGEST_VALUE_CENTS_BACKFILL_INTERVAL`, `RADAR_INGEST_VALUE_CENTS_BACKFILL_BATCH_SIZE` | desligado, 10 min, 5 000 linhas por lote | não |
| Painel de operação e DLQ | `RADAR_OPS_CONSUMER_GROUPS`, `RADAR_OPS_KAFKA_TIMEOUT`, `RADAR_OPS_REPUBLISH_TIMEOUT`, `RADAR_OPS_DLQ_RECORDER_ENABLED` | grupos `search-projector`, `alerts-matcher`, `alerts-notifier`, `ops-dlq-recorder`; 5 s; 10 s; ligado | não |
| Backpressure do projetor (ADR-0026) | `RADAR_SEARCH_PROJECTOR_BACKPRESSURE_SLOW_AFTER`, `RADAR_SEARCH_PROJECTOR_BACKPRESSURE_WINDOW`, `RADAR_SEARCH_PROJECTOR_BACKPRESSURE_PAUSE_FOR`, `RADAR_SEARCH_PROJECTOR_BACKPRESSURE_CHECK_INTERVAL` | 250 ms, 20 registros, 10 s, 1 s (chutes, sem ajuste por medida; a projeção normal leva uns 3 ms por registro); janela menor que 1 ou duração não positiva impede a aplicação de subir | não |

### Sobreposições dos apps (ADR-0028)

`ingestor` e `search-api` usam o mesmo `application.properties` do `core` e acrescentam um arquivo por app (`application-ingestor.properties`, `application-search-api.properties`), ativado pelo perfil que o `main` de cada app liga. Não são variáveis de ambiente: são o que distingue o papel do processo, e a ligação relaxada do Boot ainda deixa uma variável sobrescrevê-los.

| Chave | Ingestor | Search-api | Monolito (`core`) | Motivo |
|---|---|---|---|---|
| `spring.flyway.enabled` | ligado (padrão): é dono das migrations | `false` | ligado | a `search-api` só sobe com o schema já migrado; o compose a faz esperar o ingestor |
| `radar.outbox.relay-enabled` | ligado (padrão) | `false` | ligado | só o ingestor publica o outbox no Kafka; na `search-api` nenhum agendador dispara o relay nem a limpeza |
| `radar.ops.dlq-recorder.enabled` | `false` | ligado (padrão) | ligado | o gravador de DLQ consome os tópicos dos consumidores, que rodam na `search-api` |

## Achados

- Nenhum segredo de produção em arquivo versionado. Os únicos valores secretos no repositório são padrões de desenvolvimento: `radar_rw_dev` (senha do papel de leitura, só fora da imagem), `radar-web-dev-secret` e `radar-ops-dev-secret` (clientes do realm de desenvolvimento em `infra/keycloak/realm-radar.json`, repetidos em `infra/compose/.env.example` e usados por `scripts/ops-token.sh`) e `troque-me` (admin do Keycloak local).
- Corrigido nesta auditoria: a imagem subia com `radar_rw_dev` se ninguém definisse a senha do papel de leitura. O perfil `json` agora exige a variável, e o `values-smoke.yaml` do Helm passa um valor. O teste `JsonProfileSecretsTest` guarda o comportamento.
- `application-aot-train.properties` tem usuário e senha `aot` e uma URL de banco fictícia: é o perfil do treino do AOT cache na construção da imagem, sem Flyway e sem conexão, e nunca roda com dado real.
- Os limites do container (`mem_limit` do compose, `resources` do Helm) e os intervalos de agendamento são por ambiente e ficam no compose e nos values, não no código.
- O chart do Helm declara no ConfigMap o endereço do Kafka e o que está em `config` no `values.yaml`: URL do banco, `RADAR_HTTP_TRUST_FORWARDED`, `RADAR_PUBLIC_BASE_URL`, `RADAR_MAIL_FROM`, `RADAR_MESSAGING_*`, `RADAR_OUTBOX_MODE` e os dois tamanhos de pool. O que depende do ambiente e não tem chave ali (`RADAR_OIDC_ISSUER`, `RADAR_PNCP_BASE_URL`, `SPRING_MAIL_*`, `SPRING_DATA_REDIS_HOST`, `OTEL_EXPORTER_OTLP_ENDPOINT`) entra por `config` no values do ambiente. Os segredos chegam por `secret.values` ou `secret.existingName`. O chart roda uma réplica só (ADR-0028) e não tem a forma dividida.
- O `user_data` do Terraform não define variável de aplicação: roda `scripts/compose-init.sh` e sobe o compose, que traz o ambiente da aplicação (ver `infra/compose/docker-compose.yml`). Só `RADAR_TAG` é passada por ele, com a tag da imagem puxada do GHCR.
