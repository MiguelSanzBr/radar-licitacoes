# Mutação: pacote `ingest.domain`

Data: 22/10/2026 (reexecutado após os ajustes de revisão: guarda de snapshot antigo, `publishedAt` congelado, `restore` enxuto)

Comando:

```
./mvnw -Pmutation verify -pl core
```

Ferramenta: PIT 1.30.1 com pitest-junit5-plugin 1.2.3, mutadores padrão (`DEFAULTS`). Meta do PR 1.2: pelo menos 95% de mutantes mortos em `ingest.domain`; limiar global do build: 85%.

## Resultado

Linha do resumo do PIT para o pacote:

| pacote | classes | cobertura de linhas | cobertura de mutação | força dos testes |
|---|---|---|---|---|
| `br.dev.miguelsanzbr.radar.ingest.domain` | 6 | 100% (226/226) | 100% (126/126) | 100% (126/126) |

Por classe: `ContentHash` 41/41, `Organ` 2/2, `PncpSnapshot` 8/8, `Procurement` 46/46, `ProcurementStatus` 16/16, `ProposalWindow` 13/13.

Verificação extra com `-Dmutators=STRONGER` (mais mutadores, fora do gate): 171/171 mortos (100%) no mesmo pacote.

## Observação

Os records de valor (`OrganUnit`, `Modality`, `DisputeMode`, `LegalBasis`, `PncpSituation`, `SituationHistoryEntry`) e os eventos não geram mutantes nos mutadores padrão, pois só têm métodos gerados e `Objects.requireNonNull`. As validações deles são cobertas por testes unitários mesmo assim.
