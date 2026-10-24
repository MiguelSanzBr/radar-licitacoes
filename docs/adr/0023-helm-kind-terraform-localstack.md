# ADR-0023: Helm testado em kind e Terraform validado contra LocalStack

- Status: aceita
- Data: 2026-10-15

## Contexto

O projeto precisa mostrar que pode rodar em nuvem sem manter um ambiente pago no ar. Um ambiente permanente custaria todo mês e exigiria operação contínua. Ao mesmo tempo, o repositório precisa provar que a infraestrutura como código é válida e coerente, para que quem avaliar o projeto consiga verificar sem gastar dinheiro com a AWS.

Para Kubernetes o repositório traz um chart Helm. Ele é validado sem cluster pago: schema e instalação real num cluster `kind` descartável.

## Decisão

Helm e kind:

- O chart fica em `infra/helm/radar/` e instala só a aplicação: Deployment, Service, ConfigMap, Secret (só com `secret.create=true`, senão referencia um Secret existente), ServiceAccount, HPA e PDB (`minAvailable: 1`), que só renderizam com mais de uma réplica. O chart, porém, **recusa** `replicaCount > 1` e `autoscaling.enabled`: o monólito roda agendadores (coleta, resumo diário, limpeza do outbox) e o replay em memória, que assumem um processo só, até haver um Deployment por app (ADR-0028). O desvio `allowMultipleReplicas` existe só para o `make helm-lint` validar HPA e PDB no schema. Postgres e Kafka são dependências externas, informadas por values.
- O Pod tem probes de startup (liveness, 30 tentativas a cada 2 s), liveness e readiness nos endpoints do actuator, todas com `timeoutSeconds: 3`, requests de 250m/512Mi e limites de 1 CPU/768Mi, usuário 10001 não root, sistema de arquivos raiz somente leitura com `emptyDir` em `/tmp`, `terminationGracePeriodSeconds: 30` e `preStop` nativo (`sleep`) de 5 s.
- A aplicação usa `server.shutdown=graceful` com `spring.lifecycle.timeout-per-shutdown-phase=20s`; um teste de contexto confere os dois valores. Os 5 s do `preStop` mais os 20 s cabem nos 30 s do Pod, e o template falha se `preStopSleepSeconds + shutdownPhaseSeconds` não for menor que `terminationGracePeriodSeconds`.
- `make helm-lint` roda `helm lint` e `kubeconform -strict` sobre o `helm template`, com os valores padrão e com todos os opcionais ligados (HPA e PDB, pelo desvio de teste), e confere que o render falha sem Secret, com grace period curto, com autoscaling ligado e com `replicaCount: 2`.
- `make helm-smoke` cria o cluster `radar-smoke` (imagem do nó fixada por digest), carrega `radar-licitacoes:local`, aplica um Postgres descartável (`infra/helm/smoke/postgres.yaml`), instala o chart com `--wait`, consulta `/actuator/health/readiness` por `port-forward` e apaga o cluster ao sair. Helm, kind, kubectl e kubeconform têm versão e checksum fixados em `scripts/helm-tools.sh`, que reinstala em `~/.local/bin` qualquer binário ausente ou em versão diferente da fixada (o `PATH` não é confiado).
- Em produção o Secret deve vir de fora do chart (`secret.existingName`, por exemplo de um gerenciador de segredos); `secret.create` existe só para o smoke e para demos.
- No CI o job `helm` roda os dois alvos, com a imagem construída antes e `helm/kind-action` fixada por commit.

Terraform:

- O Terraform fica em `infra/terraform/` com quatro módulos (`network`, `compute`, `database`, `backup_bucket`) e o ambiente `envs/demo`.
- A validação roda com `fmt`, `validate`, `tflint` com o ruleset AWS e `plan` contra LocalStack (`localstack/localstack:4.14`).
- O `plan` usa um override de provider que aponta todos os endpoints para o LocalStack e usa as credenciais literais `test`. O override vive em `infra/terraform/localstack/localstack.tfoverride`, com extensão que o Terraform não carrega, e é copiado para um diretório temporário só durante o plan. O ambiente versionado nunca recebe o override.
- O `apply` nunca é executado. Está documentado, mas não existe job que o rode nem credencial AWS no repositório.
- Compute escolhido: uma EC2 `t3.medium` (x86_64, Amazon Linux 2023) com Docker instalado pelo `user_data`, que sobe o compose do projeto. A arquitetura é x86_64 porque o `main.yml` publica no GHCR uma imagem só `linux/amd64` (runner `ubuntu-latest`); a primeira versão do módulo usava `t4g` (arm64), e a imagem publicada não rodaria nela. A alternativa, manter arm64 e publicar a imagem multi-arch, exigiria QEMU ou runner arm64 no CI e foi preterida pelo custo de build. Kafka roda em container nessa mesma máquina. O IMDSv2 é obrigatório com hop limit 2, para que containers alcancem o role da instância.
- O `user_data` roda `scripts/compose-init.sh` antes do `docker compose up` (sem ele o compose não tem `.env` nem os segredos), puxa a imagem do GHCR (`image_registry` e `image_tag`, variáveis obrigatórias: o `main.yml` publica só o sha do commit e as tags semver, não há `latest`) e a marca com o nome local `radar-licitacoes:<tag>`, que o compose lê por `RADAR_TAG`. O pacote no GHCR precisa ser público, porque o `user_data` não faz login. O plan versionado em `docs/infra/plan-2026-10-22.txt` é anterior a essa mudança; `make tf-plan` o refaz.
- Banco: RDS PostgreSQL 17 `db.t4g.micro`, 20 GB gp3, Single-AZ, sem acesso público, retenção de backup de 1 dia. O ambiente de desenvolvimento local usa `postgres:18`, mas não está verificado se a RDS oferece a 18 nessa classe; por isso o RDS fica em 17 e a troca depende dessa confirmação.
- Armazenamento de backups: bucket S3 com versionamento, SSE-S3, bloqueio de acesso público, política que nega acesso sem TLS (`aws:SecureTransport = false`), expiração de objetos e versões antigas em 30 dias e cancelamento de uploads multipart incompletos em 7 dias.
- Sem MSK: Kafka em container na EC2 para reduzir custo.

## Alternativas rejeitadas (e por quê)

- ECS Fargate: alternativa válida para o compute, com menos operação de host. Foi preterida agora porque o compose atual já é o ambiente oficial e uma única EC2 mantém o custo mínimo. Continua registrada como caminho de migração.
- MSK (Kafka gerenciado): custo fixo alto para um demo. Kafka em container cobre o uso do projeto.
- Aplicar o ambiente de verdade para validar: gera custo recorrente e exige credencial real. Rejeitado pela regra de custo zero.
- Chart sem teste de instalação (só `helm lint`): não pega probe errada, imagem que não sobe nem Secret ausente. O smoke em kind cobre isso por poucos minutos de CI.
- Só `validate`, sem `plan`: não prova o grafo de dependências entre módulos nem as referências entre recursos. O `plan` contra LocalStack cobre isso sem credencial real.

## Consequências

O que o smoke em kind prova:

- O chart renderiza e os manifestos são válidos no schema do Kubernetes 1.37 (`kubeconform -strict`).
- A imagem real sobe no cluster, passa pelas probes de startup, liveness e readiness reais e o Pod fica Ready com Postgres 18 e as migrações do Flyway.
- Segurança do Pod: o processo roda com usuário 10001 e raiz somente leitura (a aplicação sobe só com `/tmp` gravável).
- O desligamento gracioso, medido: ao escalar o deployment a zero, o log do Pod contém `Commencing graceful shutdown` e o Pod some em menos de 25 s (o kubelet manda SIGKILL aos 30 s). O smoke falha se qualquer das duas coisas não acontecer. O tempo é medido sem tráfego.

O que o smoke em kind não prova:

- Kafka: o kind não tem broker (RAM), então o smoke desliga a falha rápida e a criação de tópicos do admin e prova só que a prontidão não depende do broker, não a publicação de eventos.
- Rede de nuvem real (balanceador, Ingress, DNS, políticas de rede).
- Classes de armazenamento e volumes persistentes: o Postgres do smoke não tem volume.
- Autoscaling sob carga: o HPA só é validado no schema, e o kind não instala metrics-server.
- Agendamento em vários nós, afinidade e comportamento do PDB durante drenagem de nós.
- Perda de requisições durante o desligamento sob tráfego.

O que a validação do Terraform prova:

- Sintaxe dos arquivos HCL e formatação.
- Schema do provider AWS (nomes de argumentos e tipos) para a versão fixada.
- Referências entre módulos e recursos.
- Grafo de dependências do plan: 26 recursos a criar, sem erro de ordem.
- Padrões do tflint com o ruleset AWS.
- Validação de entrada: `ssh_cidr` aceita só prefixo /16 ou mais restrito e rejeita 0.0.0.0/0.

O que a validação do Terraform não prova:

- Que o `apply` funciona: colisão de nome de bucket S3 (nome global), erros de API na criação dos recursos, timeouts da RDS e ordem real de criação.
- Permissões IAM: a policy do bucket e da role da EC2 não é avaliada contra uma conta real.
- Disponibilidade real da AMI: no LocalStack o `aws_ami` retorna um ID falso (`ami-0bb7267a511c0a8e8`). A busca por Amazon Linux 2023 x86_64 é validada só na sintaxe do filtro.
- Quotas de conta (vCPU, EIPs, instâncias RDS) e disponibilidade de classes ou versões de RDS na região, incluindo a versão 18 no `db.t4g.micro`.
- Custos reais. A estimativa está em `docs/infra/cost.md`, com preços ainda não verificados.
- Comportamento do `user_data` (instalação do Docker, download do Compose, subida do stack): roda só numa EC2 de verdade.

Pontos de atenção aceitos para o demo:

- As sub-redes do banco são as públicas, porque o escopo pediu duas sub-redes. O RDS fica sem acesso público e só aceita a porta 5432 do security group da EC2, mas uma versão de produção deveria ter sub-redes privadas.
- O download do plugin do Compose no `user_data` não confere checksum.
- `skip_final_snapshot = true` no RDS: aceitável para demo, não para uso real.

Adiamento explícito:

- O banco ainda não está ligado à aplicação. A role da EC2 não tem `secretsmanager:GetSecretValue` para ler a senha gerenciada pela RDS, e o `user_data` não recebe o `db_address`. Essa ligação fica para a tarefa do compose e da aplicação, e não está coberta por este ADR.

## Prova

- `make helm-lint` e `make helm-smoke` rodaram localmente, com saídas registradas no relatório da tarefa 7.3. O job `helm` do CI foi escrito, mas ainda não foi executado no GitHub Actions. O YAML do workflow foi validado com actionlint.
- `make tf-fmt`, `make tf-validate`, `make tf-lint` e `make tf-plan` rodaram localmente, com saídas registradas no relatório da tarefa 7.4. O job `infra` do CI foi escrito, mas ainda não foi executado no GitHub Actions. O YAML do workflow foi validado com actionlint.
- Plan salvo em `docs/infra/plan-2026-10-15.txt`, sem segredos: a senha do banco fica no Secrets Manager e o plan só mostra `master_user_secret` como "known after apply".
