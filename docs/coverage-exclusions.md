# Exclusões de cobertura

Regra: somente código gerado pode ser excluído do gate de cobertura (JaCoCo e PIT), com exclusão por caminho quando a anotação `@Generated` do gerador não sobrevive à compilação (retenção `SOURCE`). Nenhuma exclusão de código escrito à mão.

Toda `<exclude>` em `pom.xml` precisa constar nesta tabela, com o motivo. O script `scripts/check-coverage-policy.sh` falha o build se houver exclusão fora da lista.

| padrão | motivo |
|---|---|
| `br/dev/miguelsanzbr/radar/api/generated/**` | JaCoCo: interfaces e modelos gerados pelo openapi-generator a partir de `radar-api.v1.yaml`. A anotação `@Generated` do gerador é `jakarta.annotation.Generated`, que tem retenção `SOURCE` (verificado com `javap` nas classes compiladas: não há `RuntimeVisibleAnnotations` com ela), então o filtro automático do JaCoCo não a enxerga e a exclusão é por caminho. O código vive só em `target/generated-sources/openapi`. |
| `br.dev.miguelsanzbr.radar.api.generated.*` | PIT: mesmo código gerado, mesmo motivo. Mutar getters e `equals` de DTO gerado não testa nada escrito à mão. |

## Filtros do SpotBugs

Não são exclusões de cobertura e o script de política não os verifica.

- `core/spotbugs-exclude.xml`: cobre `br.dev.miguelsanzbr.radar.api.generated.*`, `br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated.*` (a análise reclama de `equals` não sobrescrito e de métodos sobrescrevíveis chamados em construtores, padrões do gerador) e o padrão `EI_EXPOSE_REP2` nos casos de uso de `br.dev.miguelsanzbr.radar.alerts.application`, que guardam ports (interfaces) recebidas por construtor, na classe `br.dev.miguelsanzbr.radar.outbox.OutboxWriter`, que guarda o `JdbcTemplate` injetado, nos casos de uso de `br.dev.miguelsanzbr.radar.identity.application` (ports) e na classe `br.dev.miguelsanzbr.radar.identity.adapter.out.redis.RedisRateLimiter`, que guarda o `StringRedisTemplate` injetado; os alertas são falsos positivos de injeção de dependência. No contexto `ops`, `HealthPanel` (listas copiadas no construtor, imutáveis) e `DeadLetters` (guarda o `MeterRegistry` injetado) entram nos mesmos dois padrões. Os DTOs gerados expõem listas mutáveis (`EI_EXPOSE_REP`, 27 achados, todos nesse pacote). Código escrito à mão continua sob a análise completa.
## Código gerado sem exclusão

O modelo jOOQ do schema `search` (pacote `br.dev.miguelsanzbr.radar.search.adapter.out.jooq.generated`) é gerado a cada build a partir das migrations reais (`core/src/codegen/JooqCodegen.java`, executado na fase `generate-sources`). O gerador anota cada classe com `org.jooq.Generated`, que tem retenção RUNTIME, então o JaCoCo e o PIT ignoram essas classes pela própria anotação. Por isso não há linha na tabela acima.
