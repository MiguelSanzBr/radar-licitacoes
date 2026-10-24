# Memória do compose

Medido em 2026-10-22 com `docker stats --no-stream` e `memory.peak` do cgroup de cada container, host de 12 GB com outras cargas, pilha completa (sem o perfil `layer3`) depois de `make up` e `make seed` (1.633 editais carregados, outbox publicado, métricas fluindo para o `otel-lgtm`).

| Serviço | Uso após o seed (MiB) | Pico do cgroup (MiB) | Limite (MiB) |
| --- | --- | --- | --- |
| `otel-lgtm` | 1.285 | 1.539 | 1.792 |
| `keycloak` | 542 | 740 | 896 |
| `app` | 486 | 551 | 640 |
| `kafka` | 330 | 382 | 512 |
| `pncp-fixture` | 104 | n/m | 256 |
| `postgres` | 86 | 135 | 256 |
| `mailpit` | 29 | n/m | 64 |
| `redis` | 15 | n/m | 96 |
| **Total** | **2.877** | | |

Antes do seed a soma era de 1,98 GiB (aplicação, Kafka e `otel-lgtm` crescem com a carga). O pico do cgroup inclui cache de página, que o kernel devolve antes de matar o processo. O Keycloak em `start-dev` fica perto do limite só durante a compilação do servidor na partida. n/m: não medido.

## Perfil split (ADR-0028)

Medido em 2026-10-23 do mesmo jeito (`docker stats --no-stream` e `memory.peak` do cgroup), com `PROFILE=split make up` e `RADAR_APP_URL=http://localhost:8082 make seed` esperando os 1.633 editais chegarem a `search.procurement_search_view`. Host de 12 GB com outras cargas.

| Serviço | Uso após o seed (MiB) | Pico do cgroup (MiB) | Limite (MiB) |
| --- | --- | --- | --- |
| `otel-lgtm` | 996 | 1.228 | 1.792 |
| `keycloak` | 545 | 854 | 896 |
| `kafka` | 443 | 466 | 512 |
| `ingestor` | 438 | 481 | 576 (era 512) |
| `search-api` | 393 | 401 | 640 |
| `postgres` | 124 | 180 | 256 |
| `pncp-fixture` | 105 | 135 | 256 |
| `redis` | 16 | 24 | 96 |
| `mailpit` | 15 | 27 | 64 |
| **Total** | **3.075** | | |

Dois JVMs custam cerca de 0,35 GiB a mais que o `app` sozinho (831 MiB contra 486 MiB). O pico do ingestor com 512 MB chegou a 481 MiB (inclui cache de página), então o limite subiu para 576 MB; a `search-api` tem folga no 640 MB.

