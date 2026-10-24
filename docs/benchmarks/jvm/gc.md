# Coletor de lixo: Serial × G1 × ZGC (PR 6.2)

Data: 2026-10-23. Imagem `radar-licitacoes:local` construída do commit `0f83eb4` da `main` (JRE Temurin 25.0.4.1 gerado pelo `jlink` da imagem, AOT cache treinado no build). Scripts na versão do commit `439441b` (`scripts/profile.sh`, `scripts/gc-pauses.sh`). Máquina: AMD Ryzen 5 5600GT (12 threads), 11,6 GiB de RAM, Linux 6.12, Docker 29.9.2, Postgres 18.6. Os números brutos estão em `gc-serial.json`, `gc-g1.json`, `gc-zgc.json` e `gc-zgc-1g.json`.

## Método

`make profile GC=serial|g1|zgc|all` (`scripts/profile.sh`) sobe o compose com `docker-compose.bench.yml` (limite do app em **640m**, o do compose; Postgres com 2 GB e `shared_buffers` de 512 MB; sem limite de requisições), carrega a massa **uma vez** e depois, para cada coletor, reinicia só a aplicação:

- **Massa (a mesma nos três):** o dia real do seed (1.633 licitações) mais **200.000 licitações sintéticas rotuladas** (`source = 'synthetic'`, `tools/synth`, semente 42), publicadas como eventos no tópico `radar.procurement.v1` e projetadas pelo próprio app. Total: 201.633 linhas na visão de busca, **inclui dados sintéticos**. O tópico guarda 201.738 eventos (200.000 sintéticos mais os do dia real).
- **JVM:** `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Xms64m -XX:+ExitOnOutOfMemoryError` mais `-XX:+UseSerialGC`, `-XX:+UseG1GC` ou `-XX:+UseZGC`, e `-Xlog:gc*:file=/tmp/gc.log:utctime,level,tags`, copiado do container ao fim. Heap máximo de 480 MB (75% de 640 MB) nos três.
- **Carga:** Gatling S1 (busca textual, 50 usuários, 1 s de pensamento), 1 min de aquecimento e 3 min medidos; em seguida `make replay` (reconstrói a visão de busca do offset 0 do tópico, lotes de 500 eventos por transação).
- **Medidas:** latência e rps do Gatling (só a fase medida); pausas de GC do log (`scripts/gc-pauses.sh`: linhas `Pause ... ms`; no ZGC, as fases `Pause` de `gc,phases`); CPU do cgroup do container (`cpu.stat`); memória pelo `docker stats` (pico), pelo `VmHWM` do processo da JVM (RSS de pico, que conta páginas mapeadas de arquivo e, no ZGC, o heap em `memfd`) e pelo `memory.peak` do cgroup. As pausas do S1 cobrem a janela inteira da execução do Gatling (compilação, aquecimento e medição); as do replay, só o replay.
- **ZGC é geracional no JDK 25:** `-XX:+UseZGC` basta (o modo não geracional foi removido no JDK 24, JEP 490). O log confirma: "GC Workers for Old Generation" e "for Young Generation" na inicialização e fases com prefixo `y:`.

Cada coletor foi medido **uma vez**, na ordem Serial, G1, ZGC (a base já estava aquecida na primeira medida, pela projeção). Há ruído de execução para execução: o replay variou de 868 a 1.520 eventos/s em quatro execuções do `ProjectionReplayIT` (`docs/benchmarks/replay/2026-10-22.md`), então diferenças de vazão abaixo de uns 10% não são conclusivas. As pausas e a memória são os sinais claros.

## Resultado sob 640 MB

| | Serial | G1 | ZGC |
| --- | ---: | ---: | ---: |
| **S1** requisições (medidas), erros | 8.918, 0 | 8.909, 0 | 8.917, 0 |
| S1 rps | 36,7 | 36,8 | 36,9 |
| S1 p50 / p95 / p99 (ms) | 7 / 18 / 24 | 8 / 19 / 27 | 8 / 19 / 45 |
| S1 máximo (ms) | 45 | 41 | 157 |
| S1 pausas de GC: quantidade | 146 | 180 | 118 |
| S1 pausa p50 / p99 / máx (ms) | 2,88 / 12,18 / 12,62 | 1,76 / 13,04 / 13,95 | 0,013 / 0,293 / 0,441 |
| S1 tempo total parado (ms) | 465 | 386 | 3,2 |
| S1 CPU do container (s, 4 min) | 47,5 | 57,4 | 52,4 |
| **Replay** eventos, duração | 201.738, 306 s | 201.738, 279 s | 201.738, 309 s |
| Replay vazão (eventos/s) | 659 | 724 | 653 |
| Replay CPU do container (s) | 147,5 | 177,8 | 167,0 |
| Replay pausas: quantidade | 656 | 785 | 1.143 |
| Replay pausa p50 / p99 / máx (ms) | 2,75 / 26,9 / 466,8 | 1,54 / 5,74 / 11,89 | 0,013 / 0,353 / 162,7 |
| Replay tempo total parado (ms) | 3.010 | 1.420 | 405 |
| GCs completos (Full) na execução toda | 8 | 0 | n/a |
| Paradas por alocação (`Allocation Stall`) | n/a | n/a | 442 |
| **Memória** RSS de pico após o S1 / após o replay (MiB) | 473 / 653 | 490 / 592 | 658 / 659 |
| Pico no `docker stats` (S1 / replay, MiB) | 501 / 640 | 573 / 548 | 640 / 640 |
| Pico do cgroup (`memory.peak`, MiB) | 640 | 640 | 640 |
| OOM kill, reinícios | não, 0 | não, 0 | não, 0 |

O pico do cgroup chega ao limite nos três porque inclui cache de página, que o kernel devolve antes de matar o processo (ver `docs/benchmarks/compose-memory.md`). O que diferencia os coletores é o RSS da JVM e o `docker stats`.

## ZGC com mais folga (nota, 1 GB)

O ZGC não caiu nem foi morto sob 640 MB, mas ficou sem folga: RSS e `docker stats` no limite, 442 paradas por alocação (o ZGC faz a thread de aplicação esperar a coleta), p99 de 45 ms e máximo de 157 ms no S1, e duas pausas de 129 ms e 163 ms no replay (provavelmente pressão de memória do cgroup: o ZGC reserva o heap em `memfd` e não devolve páginas durante a carga). Uma execução à parte com `PROFILE_APP_MEM=1g make profile GC=zgc` (`gc-zgc-1g.json`):

| ZGC | 640 MB | 1 GB |
| --- | ---: | ---: |
| S1 p50 / p95 / p99 / máx (ms) | 8 / 19 / 45 / 157 | 8 / 18 / 27 / 41 |
| S1 pausa máx (ms) | 0,441 | 0,134 |
| Replay vazão (eventos/s) | 653 | 690 |
| Replay pausa máx (ms) | 162,7 | 0,291 |
| Paradas por alocação | 442 | 199 |
| RSS de pico (MiB) | 659 | 1.042 |

Com 1 GB as pausas somem (máximo de 0,29 ms) e a latência iguala a do G1 sob 640 MB, mas a JVM passa a ocupar o 1 GB inteiro: 60% a mais de memória por uma cauda que o G1 já mantém em 14 ms.

## Escolha: G1

**G1, fixado em `JAVA_TOOL_OPTIONS` da imagem** (`-XX:+UseG1GC`), mantendo os 640 MB.

- **Sem pausa longa.** O Serial parou 467 ms num GC completo sob pressão de alocação no replay (e 8 GCs completos no total, dois deles de 125 ms na subida, por limite de metadados); o G1 não fez nenhum GC completo e o máximo foi de 14 ms no S1 e 12 ms no replay. Para uma API de busca com meta de p99 em dezenas de milissegundos e uma operação de replay que roda sob tráfego, um corte de 467 ms para 12 ms é o ganho que importa.
- **Cabe nos 640 MB.** O RSS do G1 no fim do replay (592 MiB) ficou abaixo do Serial (653 MiB), e o `docker stats` terminou em 548 MiB contra 640 MiB. O ZGC não tem folga nesse limite e só se comporta com 1 GB.
- **Vazão do replay igual ou melhor** (724 contra 659 e 653 eventos/s), dentro da faixa de ruído, mas sem sinal contrário.
- **Latência do S1** equivalente entre Serial e G1 (p99 de 24 e 27 ms, com cerca de 90 amostras na cauda: diferença dentro do ruído). A tabela do S1 é limitada pelo tempo de pensamento (rps de 36,7 a 36,9 nos três), e a busca é dominada pela espera do Postgres (`docs/benchmarks/jvm/profile-before-after.md`), então o coletor quase não aparece nela.

**Custo do G1.** Mais CPU: 21% a mais que o Serial no S1 (57,4 contra 47,5 s em 4 minutos) e 21% a mais no replay (177,8 contra 147,5 s), por causa dos threads concorrentes de marcação e das barreiras de escrita; e 17 MiB a mais de RSS que o Serial no S1 (490 contra 473). Num único container de 640 MB com 12 núcleos no host a CPU extra é folga; se o app for colocado num limite de CPU apertado (por exemplo 0,5 vCPU no Kubernetes), o Serial volta a ser a opção a medir.

**Por que fixar o coletor.** Sem `-XX:+UseG1GC` a ergonomia da JVM escolhe o Serial quando a memória do container é menor que 1792 MB ou há menos de 2 processadores, e o G1 acima disso: o app rodava Serial só porque o limite era de 640 MB, e mudar o limite mudaria o coletor sem aviso. O AOT cache segue válido: o teste de container (`RadarImageIT`, 4 testes, sob 640 MB) confere "Using AOT-linked classes: true" sem aviso de cache com a imagem já em G1.

**Quando rever.** Se o limite do app subir para 1 GB ou mais e a cauda de latência (p99) passar a ser requisito, o ZGC geracional passa a valer a pena (pausas abaixo de 1 ms). Se o limite descer ou a CPU for o recurso escasso, o Serial.

## Como repetir

```
make image                      # imagem radar-licitacoes:local
make profile GC=all             # serial, g1 e zgc; cerca de uma hora, uns 4,5 GB de RAM livres
PROFILE_APP_MEM=1g make profile GC=zgc
CLEAN=1 make down
```

A pilha fica no ar entre as execuções (a massa leva mais de 15 minutos para carregar); `PROFILE_ROWS`, `PROFILE_USERS` e `PROFILE_MEASURED_S` ajustam o tamanho. O log do GC de cada execução fica em `perf/target/profile/gc-<gc>.log` (não versionado).
