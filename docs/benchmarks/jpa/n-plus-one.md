# Prova: carga de licitações com histórico sem N+1

Data: 2026-10-18

## O que foi medido

Teste `ProcurementNPlusOneIT` (Postgres 18 via Testcontainers, Hibernate com `generate_statistics=true`): salva 50 licitações com 3 entradas de histórico cada (descoberta, suspensão, retomada) e mede, com `Statistics.getPrepareStatementCount()`, duas formas de carregar os mesmos 50 ids depois de limpar o contexto de persistência. O teste registra os dois números no log (`n-plus-one: 50 procurements, one findById each = N statements, one findAllById = M`) e exige no máximo 2 statements para `findAllById`.

Comando:

```
./mvnw -pl core verify -Dit.test=ProcurementNPlusOneIT -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
```

## Resultado

| Estratégia | Statements para 50 licitações com 3 entradas cada |
|---|---|
| Associação JPA preguiçosa (mapeamento anterior, consulta sem `join fetch`) | 51 (1 das licitações + 1 por licitação para o histórico) |
| Um `findById` por id (o que o chamador faria sem a carga em lote) | 100 (2 por licitação) |
| `findAllById` atual: licitações e histórico em duas consultas | 2 |

O 51 foi medido na versão anterior deste PR, antes de a associação ser removida (consulta sem `left join fetch`; o teste falhou com 51). O 100 e o 2 saem do teste atual.

## Como funciona

`findAllById` faz uma consulta das licitações (`numero_controle_pncp in (...)`, servida pelo índice da chave primária de cada partição) e uma do histórico (`where numero_controle_pncp in (...) order by recorded_at, id`). O mapper agrupa as linhas de histórico por número, já ordenadas. Cada lote de até 1000 ids custa 2 statements (1001 ids custam 4, coberto por teste), o que mantém a lista do `IN` longe do limite de parâmetros do driver.
