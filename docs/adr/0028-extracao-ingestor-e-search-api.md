# ADR-0028: Extração de `ingestor` e `search-api`

- Status: aceita
- Data: 2026-10-23

## Contexto

O Radar é um monólito modular (ADR-0003): `core` é um único app Spring Boot com os módulos `ingest`, `outbox`, `search`, `alerts`, `identity`, `ops` e `observability`. As fronteiras são verificadas por ArchUnit e Modulith, então os módulos já se falam só por evento, pelo outbox e por tabelas próprias.

As duas metades da plataforma têm perfis de carga opostos:

- A **ingestão** é I/O e agenda. O PNCP responde em torno de 0,2 requisição por segundo (ADR-0017), a coleta incremental roda a cada 5 minutos e o relay do outbox é um laço único por trava de banco. Ela não ganha nada com mais réplicas (duas coletas simultâneas só disputam a mesma cota do PNCP) e um bloqueio do PNCP não pode afetar a leitura.
- A **busca** é CPU e latência. Cada consulta é uma leitura no Postgres, o caminho mais quente do sistema (ADR-0011), a carga é de rajada e escala horizontalmente, porque as réplicas leem o mesmo read model e o contador de limite está no Redis (ADR-0027).

Hoje os dois dividem JVM, pool de conexões e ciclo de deploy.

## Decisão

Dois apps Spring Boot em `apps/`, ao lado do monólito, que continua existindo como padrão de desenvolvimento (`make up`) e de teste. `core` passa a ser também biblioteca.

**Quem fica com quê.**

| App | Módulos | Observação |
| --- | --- | --- |
| `apps/ingestor` | `ingest`, `outbox`, `ops`, `observability`, mais `SecurityConfig` de `identity` | coleta agendada, `/ops/backfill`, relay e limpeza do outbox. Só a configuração de segurança do `identity` entra: as rotas `/ops` e `/actuator` precisam das duas cadeias de filtros, mas a ingestão não tem chave de API nem limite de requisições |
| `apps/search-api` | `search`, `alerts`, `identity`, `ops`, `observability`, `outbox` | API pública, páginas, projetor, matcher e notificador de alertas, chaves de API, painel. O pacote `outbox` entra porque os alertas gravam no outbox (`OutboxWriter`) e o painel lê os medidores dele; o relay fica desligado (`radar.outbox.relay-enabled=false`) e só o ingestor publica |

`ops` vai para os dois: `ingest` depende dele pelas flags (Togglz) e o painel lê tabelas compartilhadas. O gravador de DLQ (`radar.ops.dlq-recorder.enabled`) só roda na `search-api`, que é quem tem consumidores.

**Alertas ficam com a busca**, não com a ingestão: a busca reversa (ADR-0015) usa a configuração de texto do schema `search` (`search.portuguese_unaccent`), então o casamento tem de concordar com o lado de leitura; consomem o mesmo tópico do projetor, expõem rotas na mesma API pública e usam o mesmo `identity`. Separá-los de `search` criaria um terceiro app acoplado ao mesmo schema.

**Como cada app escolhe seus módulos.** O `@SpringBootApplication` de cada um lista os pacotes dos módulos que varre (`scanBasePackages`), em vez de varrer a raiz e excluir. Os três `@Configuration` da raiz (`RadarConfiguration`, `PrimaryPersistenceConfiguration`, `RedisClientConfiguration`) tornaram-se públicos para entrarem por `@Import`. O ingestor registra o pacote `ingest` como pacote das entidades JPA (`@AutoConfigurationPackage`), a única parte do sistema que usa JPA.

**Configuração.** O `application.properties` do `core` continua sendo o padrão dos dois. Um arquivo de mesmo nome num app esconderia o do `core` no classpath, então cada app traz uma sobreposição por perfil (`application-ingestor.properties`, `application-search-api.properties`), que o `main` ativa. Só o que difere está lá.

**Migrations.** O **ingestor é o dono do Flyway** (`spring.flyway.enabled` no padrão). A `search-api` roda com `spring.flyway.enabled=false`. No compose a `search-api` espera o ingestor ficar saudável (`service_healthy`); fora essa ordem e a primeira consulta, que falha contra um schema atrasado, nada a protege (a `search-api` não tem entidades JPA, então não há `validate` do Hibernate ali). Não há um passo `migrate` separado: o ingestor é um app de réplica única, então não há corrida de migrations.

**Empacotamento.** `core` gera o jar simples (biblioteca dos apps) e o jar executável com o classificador `exec`. O `Dockerfile` ganhou `ARG MODULE` (`core`, `apps/ingestor`, `apps/search-api`) e pega `*-exec.jar` do módulo: a imagem do monólito não muda de conteúdo. `make image-split` constrói `radar-licitacoes-ingestor` e `radar-licitacoes-search-api`.

**Compose.** O perfil `split` (`PROFILE=split make up`) sobe `ingestor` (porta 8082, 576 MB) e `search-api` (porta 8080, 640 MB) no lugar do `app`. O `app` não tem perfil, para que o resto (e2e, bench, deploy, Terraform) continue vendo o monólito; o `make up` passa `--scale app=0` no perfil `split`. Os dois serviços herdam do `app` o ambiente e os segredos por âncora YAML, então não há segunda lista de variáveis. Nesse perfil a coleta é do ingestor: `make seed` e `make backfill` apontam para ele com `RADAR_APP_URL=http://localhost:8082`. Ao trocar de perfil rode `make down` antes (mesmas portas e mesmo banco).

## Alternativas rejeitadas (e por quê)

- **Excluir pacotes da varredura da raiz.** Declarativo à primeira vista, mas um módulo novo entraria nos dois apps por omissão. Listar os pacotes faz o contexto de cada app ser o que está escrito na classe.
- **Perfil ou propriedade que liga e desliga os beans do monólito.** Já existe para schedulers e consumidores, mas só desliga comportamento: o `BackfillUseCase` continuaria no contexto da `search-api` e a promessa de fronteira não teria o que verificar.
- **Um passo `migrate` separado (job ou init container).** É o desenho certo com várias réplicas escrevendo. Aqui custa um terceiro artefato para um ingestor de réplica única; fica para quando houver mais de um.
- **`app` com perfil `monolith` e padrão `COMPOSE_PROFILES`.** Quebraria `docker compose up` puro, o bench, o e2e e o `user_data` do Terraform, que não conhecem perfis.
- **Alertas como terceiro app.** Ver acima: ficariam acoplados ao schema da busca, com mais um deploy.
- **Repartir o `outbox` (escritor num lado, relay no outro).** O módulo é um só e o limite entre escrever e publicar é uma propriedade, não um pacote.

## Consequências

- **Ganhos.** A busca escala por réplica sem arrastar a coleta; uma queda ou bloqueio do PNCP não consome o orçamento de memória nem de conexões da leitura; cada app tem heap e pool próprios. A fronteira vira executável: o contexto da `search-api` não tem nenhum bean de `ingest` e o do ingestor não tem bean de `search` nem de `alerts`.
- **Custos aceitos.** Dois deploys e duas imagens; ordem de partida (a `search-api` depois do ingestor, por causa das migrations); a configuração comum precisa ser repetida em cada alvo (no compose a âncora resolve, no Helm e no Terraform seriam dois valores a manter); o relay e o gravador de DLQ vivem em apps diferentes, então um incidente de mensageria pede olhar dois logs.
- **Riscos conhecidos.** (1) Sem validação do schema na `search-api` (nem Flyway nem Hibernate), um schema atrasado aparece na primeira consulta, não na partida. (2) Os contadores `dlq_messages_total` são registrados em zero só no ingestor (`KafkaOutboxConfiguration`); na `search-api` a série nasce no primeiro incremento. (3) O perfil `split` não combina com `cdc`: o `cdc-setup` depende do serviço `app`.
- **Fora desta extração.** Chart Helm e Terraform continuam com um só Deployment e um só serviço; um segundo Deployment com o mesmo chart é trabalho futuro (valores por app, `Service` e `HPA` só para a `search-api`).
- **Quando parar no monólito.** Um nó, carga de leitura baixa e nenhuma interferência medida da coleta sobre o p95 da busca: o monólito é mais barato de operar e continua sendo o padrão. A extração se paga quando a leitura precisa de mais de uma réplica, quando a coleta e a busca disputam recursos ou quando precisam de janelas de deploy diferentes.

## Prova

- `apps/ingestor/src/test/.../IngestorContextTest` e `apps/search-api/src/test/.../SearchApiContextTest`: o contexto de cada app tem os beans do próprio lado e nenhum do outro (nenhum `BackfillUseCase` na `search-api`; nenhum bean de `search`, `alerts` ou dos adaptadores de `identity` no ingestor). `IngestorProductionContextTest` e `SearchApiProductionContextTest` repetem a conferência com as chaves de produção (agendadores, relay, matcher, notificador, projetor, limite e cache ligados; só banco, migrations e ouvintes do Kafka desativados): o ingestor tem `OutboxScheduler`, `ScheduledIncrementalSync`, `ScheduledPartitionMaintenance` e `BackfillController`; a `search-api` tem os ouvintes, o `RateLimitFilter` e o `RedisDetailCache` e não tem `OutboxScheduler`.
- `apps/ingestor/src/test/.../PartitionParityTest`: os beans do monólito são a união dos beans dos dois apps, salvo uma lista de exceções documentada no teste (nomes das classes de configuração da raiz e das classes principais).
- `core/src/test/.../architecture/ArchitectureTest` (`search_does_not_depend_on_ingest_or_outbox`) e `ModularityTest` (`searchNeverReachesIntoTheWriteSide`): `search` nunca enxerga `ingest` nem `outbox`.
- `e2e/src/test/.../SplitStackE2EIT` (`make e2e-split`, depois de `make image-split`; executado em 2026-10-23, verde em cerca de 84 s): o ingestor coleta o dia da fixture, o relay publica, a `search-api` projeta e responde `/api/v1/procurements`; a `search-api` não serve o backfill e o ingestor não serve a busca.
