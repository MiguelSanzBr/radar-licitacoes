# Exclusões de cobertura

Regra: no JaCoCo, somente código gerado pode ser excluído do gate de cobertura, com exclusão por caminho quando a anotação `@Generated` do gerador não sobrevive à compilação (retenção `SOURCE`). Nenhuma exclusão de código escrito à mão no JaCoCo.

O PIT roda só com os testes de unidade (`*IT` excluído): com os ITs, cada mutante sobe Testcontainers e contexto Spring, e o job `mutation` não terminava nem em 120 min no runner do GitHub. Por isso o PIT também exclui os adaptadores que só os ITs exercitam (banco e Redis) e não tem `coverageThreshold`; esse código continua no gate de 100% do JaCoCo, que roda com os ITs.

Toda `<exclude>` em `pom.xml` precisa constar nesta tabela, com o motivo. O script `scripts/check-coverage-policy.sh` falha o build se houver exclusão fora da lista.

| padrão | motivo |
|---|---|
| `br/dev/miguelsanzbr/radar/api/generated/**` | JaCoCo: interfaces e modelos gerados pelo openapi-generator a partir de `radar-api.v1.yaml`. A anotação `@Generated` do gerador é `jakarta.annotation.Generated`, que tem retenção `SOURCE` (verificado com `javap` nas classes compiladas: não há `RuntimeVisibleAnnotations` com ela), então o filtro automático do JaCoCo não a enxerga e a exclusão é por caminho. O código vive só em `target/generated-sources/openapi`. |
| `br.dev.miguelsanzbr.radar.api.generated.*` | PIT: mesmo código gerado, mesmo motivo. Mutar getters e `equals` de DTO gerado não testa nada escrito à mão. |
| `br.dev.miguelsanzbr.radar.*.adapter.out.jdbc.*` | PIT: adaptadores JDBC, testados só por IT contra Postgres real. |
| `br.dev.miguelsanzbr.radar.*.adapter.out.jpa.*` | PIT: adaptadores JPA do `ingest`, testados só por IT. |
| `br.dev.miguelsanzbr.radar.*.adapter.out.jooq.*` | PIT: leitura da busca com jOOQ, testada só por IT. |
| `br.dev.miguelsanzbr.radar.identity.adapter.out.redis.*` | PIT: limite de requisições no Redis, testado só por IT. |
| `br.dev.miguelsanzbr.radar.identity.adapter.config.RateLimitConfiguration*` | PIT: configuração do cliente Redis do limite, exercitada só por IT. |
| `br.dev.miguelsanzbr.radar.search.adapter.in.bench.*` | PIT: endpoint de benchmark, ligado só nos ITs de bench. |
| `br.dev.miguelsanzbr.radar.outbox.OutboxRelay*` | PIT: relay do outbox (SKIP LOCKED e advisory lock), testado só por IT. |
| `br.dev.miguelsanzbr.radar.outbox.OutboxCleanup*` | PIT: limpeza do outbox por SQL, testada só por IT. |
| `br.dev.miguelsanzbr.radar.outbox.OutboxGauges*` | PIT: métricas lidas do banco, testadas só por IT. |
| `br.dev.miguelsanzbr.radar.outbox.ReplicationSlotGauge*` | PIT: métrica do slot de replicação, testada só por IT. |
| `br.dev.miguelsanzbr.radar.synth.CopyLoader*` | PIT: carga do gerador sintético por `COPY` no Postgres (`tools/synth`), testada só por IT. |
| `*IT` | PIT (`excludedTestClasses`): os ITs ficam fora do PIT pelo tempo; seguem no `verify` e no JaCoCo. |

## Filtros do SpotBugs

Não são exclusões de cobertura e o script de política não os verifica.

- `core/spotbugs-exclude.xml`: cobre `br.dev.miguelsanzbr.radar.api.generated.*`, `br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated.*` (a análise reclama de `equals` não sobrescrito e de métodos sobrescrevíveis chamados em construtores, padrões do gerador) e o padrão `EI_EXPOSE_REP2` nos casos de uso de `br.dev.miguelsanzbr.radar.alerts.application`, que guardam ports (interfaces) recebidas por construtor, na classe `br.dev.miguelsanzbr.radar.outbox.OutboxWriter`, que guarda o `JdbcTemplate` injetado, nos casos de uso de `br.dev.miguelsanzbr.radar.identity.application` (ports) na classe `br.dev.miguelsanzbr.radar.identity.adapter.out.redis.RedisRateLimiter`, que guarda o `StringRedisTemplate` injetado, e na classe `br.dev.miguelsanzbr.radar.search.adapter.out.redis.RedisDetailCache`, que guarda o mesmo template; os alertas são falsos positivos de injeção de dependência. No contexto `ops`, `HealthPanel` (listas copiadas no construtor, imutáveis) e `DeadLetters` (guarda o `MeterRegistry` injetado) entram nos mesmos dois padrões. Os DTOs gerados expõem listas mutáveis (`EI_EXPOSE_REP`, 27 achados, todos nesse pacote). `EnvelopeSerializer` (`br.dev.miguelsanzbr.radar.outbox.adapter.kafka`) entra em `PZLA_PREFER_ZERO_LENGTH_ARRAYS`: o contrato do `Serializer` do Kafka devolve `null` para um valor nulo (o tombstone), não um array vazio. Código escrito à mão continua sob a análise completa.
## Código gerado sem exclusão

O modelo jOOQ do schema `search` (pacote `br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated`) é gerado a cada build a partir das migrations reais (`core/src/codegen/JooqCodegen.java`, executado na fase `generate-sources`). O gerador anota cada classe com `org.jooq.Generated`, que tem retenção RUNTIME, então o JaCoCo e o PIT ignoram essas classes pela própria anotação. Por isso não há linha na tabela acima.
