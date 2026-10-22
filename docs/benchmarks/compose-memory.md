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
