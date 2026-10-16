# ADR-0023: Helm testado em kind e Terraform validado contra LocalStack

- Status: proposta
- Data: 2026-10-15

## Contexto

O projeto precisa mostrar que pode rodar em nuvem sem manter um ambiente pago no ar. Um ambiente permanente custaria todo mês e exigiria operação contínua. Ao mesmo tempo, o repositório precisa provar que a infraestrutura como código é válida e coerente, para que quem avaliar o projeto consiga verificar sem gastar dinheiro com a AWS.

A parte Helm desta decisão é implementada na tarefa 7.3. Este ADR cobre a parte Terraform.

## Decisão

- O Terraform fica em `infra/terraform/` com quatro módulos (`network`, `compute`, `database`, `backup_bucket`) e o ambiente `envs/demo`.
- A validação roda com `fmt`, `validate`, `tflint` com o ruleset AWS e `plan` contra LocalStack (`localstack/localstack:4.14`).
- O `plan` usa um override de provider que aponta todos os endpoints para o LocalStack e usa as credenciais literais `test`. O override vive em `infra/terraform/localstack/localstack.tfoverride`, com extensão que o Terraform não carrega, e é copiado para um diretório temporário só durante o plan. O ambiente versionado nunca recebe o override.
- O `apply` nunca é executado. Está documentado, mas não existe job que o rode nem credencial AWS no repositório.
- Compute escolhido: uma EC2 `t4g.medium` (arm64, Amazon Linux 2023) com Docker instalado pelo `user_data`, que sobe o compose do projeto. Kafka roda em container nessa mesma máquina. O IMDSv2 é obrigatório com hop limit 2, para que containers alcancem o role da instância.
- Banco: RDS PostgreSQL 17 `db.t4g.micro`, 20 GB gp3, Single-AZ, sem acesso público, retenção de backup de 1 dia. O ambiente de desenvolvimento local usa `postgres:18`, mas não está verificado se a RDS oferece a 18 nessa classe; por isso o RDS fica em 17 e a troca depende dessa confirmação.
- Armazenamento de backups: bucket S3 com versionamento, SSE-S3, bloqueio de acesso público, política que nega acesso sem TLS (`aws:SecureTransport = false`), expiração de objetos e versões antigas em 30 dias e cancelamento de uploads multipart incompletos em 7 dias.
- Sem MSK: Kafka em container na EC2 para reduzir custo.

## Alternativas rejeitadas (e por quê)

- ECS Fargate: alternativa válida para o compute, com menos operação de host. Foi preterida agora porque o compose atual já é o ambiente oficial e uma única EC2 mantém o custo mínimo. Continua registrada como caminho de migração.
- MSK (Kafka gerenciado): custo fixo alto para um demo. Kafka em container cobre o uso do projeto.
- Aplicar o ambiente de verdade para validar: gera custo recorrente e exige credencial real. Rejeitado pela regra de custo zero.
- Só `validate`, sem `plan`: não prova o grafo de dependências entre módulos nem as referências entre recursos. O `plan` contra LocalStack cobre isso sem credencial real.

## Consequências

O que a validação prova:

- Sintaxe dos arquivos HCL e formatação.
- Schema do provider AWS (nomes de argumentos e tipos) para a versão fixada.
- Referências entre módulos e recursos.
- Grafo de dependências do plan: 26 recursos a criar, sem erro de ordem.
- Padrões do tflint com o ruleset AWS.
- Validação de entrada: `ssh_cidr` aceita só prefixo /16 ou mais restrito e rejeita 0.0.0.0/0.

O que a validação não prova:

- Que o `apply` funciona: colisão de nome de bucket S3 (nome global), erros de API na criação dos recursos, timeouts da RDS e ordem real de criação.
- Permissões IAM: a policy do bucket e da role da EC2 não é avaliada contra uma conta real.
- Disponibilidade real da AMI: no LocalStack o `aws_ami` retorna um ID falso (`ami-0bb7267a511c0a8e8`). A busca por Amazon Linux 2023 arm64 é validada só na sintaxe do filtro.
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

- `make tf-fmt`, `make tf-validate`, `make tf-lint` e `make tf-plan` rodaram localmente, com saídas registradas no relatório da tarefa 7.4. O job `infra` do CI foi escrito, mas ainda não foi executado no GitHub Actions. O YAML do workflow foi validado com actionlint.
- Plan salvo em `docs/infra/plan-2026-10-15.txt`, sem segredos: a senha do banco fica no Secrets Manager e o plan só mostra `master_user_secret` como "known after apply".
