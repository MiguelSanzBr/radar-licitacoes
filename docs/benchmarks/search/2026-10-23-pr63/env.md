# Ambiente do benchmark de busca

- Data: 2026-10-23
- Commit: `a05da33ba7c40fc5f0fa7d5781a9d99cec9a159c`
- Postgres 18.6, imagem `postgres:18@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6`
- Docker: 29.9.2, compose 5.6.1
- Kernel: Linux 6.12.113+deb13-amd64

## CPU (`lscpu`)

```
Architecture:                            x86_64
CPU(s):                                  12
Model name:                              AMD Ryzen 5 5600GT with Radeon Graphics
Thread(s) per core:                      2
Core(s) per socket:                      6
Socket(s):                               1
CPU(s) scaling MHz:                      92%
CPU max MHz:                             4668.0000
L2 cache:                                3 MiB (6 instances)
L3 cache:                                16 MiB (1 instance)
```

## Memória (`free -m`) e disco (`df -h`)

```
               total        used        free      shared  buff/cache   available
Mem:           11832        6257        2024         564        4433        5574
Swap:          12153        2878        9275
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme0n1p2  926G  840G   39G  96% /
```

## Limites dos containers (`docker-compose.yml` mais `docker-compose.bench.yml`)

```
/radar-app-1 671088640 bytes
/radar-kafka-1 536870912 bytes
/radar-keycloak-1 939524096 bytes
/radar-mailpit-1 67108864 bytes
/radar-otel-lgtm-1 1879048192 bytes
/radar-pncp-fixture-1 268435456 bytes
/radar-postgres-1 2147483648 bytes
/radar-redis-1 100663296 bytes
```

## Configuração efetiva do Postgres (`pg_settings`, só o que não é o padrão, mais os parâmetros que pesam no plano)

```
application_name = psql  [client]
autovacuum_worker_slots = 16  [configuration file]
DateStyle = ISO, MDY  [configuration file]
default_statistics_target = 100  [default]
default_text_search_config = pg_catalog.english  [configuration file]
dynamic_shared_memory_type = posix  [configuration file]
effective_cache_size = 1536MB  [command line]
jit = on  [default]
lc_messages = en_US.utf8  [configuration file]
lc_monetary = en_US.utf8  [configuration file]
lc_numeric = en_US.utf8  [configuration file]
lc_time = en_US.utf8  [configuration file]
listen_addresses = *  [configuration file]
log_timezone = Etc/UTC  [configuration file]
maintenance_work_mem = 256MB  [command line]
max_connections = 100  [command line]
max_parallel_workers_per_gather = 2  [default]
max_slot_wal_keep_size = 1GB  [command line]
max_wal_size = 1GB  [configuration file]
min_wal_size = 80MB  [configuration file]
random_page_cost = 4  [default]
shared_buffers = 512MB  [command line]
shared_preload_libraries = pg_stat_statements  [command line]
TimeZone = Etc/UTC  [configuration file]
wal_level = logical  [command line]
work_mem = 4MB  [default]
```

## Dados

- Linhas na view de busca: 1200000 (1633 reais do PNCP, 1198367 sintéticas): inclui dados sintéticos (1198367 linhas)
- Linhas no detalhe: 1200000; no stats: 1692
- Tamanho: tabela de busca 1098 MB, índices 670 MB, detalhe 1969 MB
