# Estimativa de custo mensal do ambiente demo

Região: us-east-1 (N. Virginia), preços on-demand. Data da tentativa de verificação: 2026-10-15.

## Situação dos preços

Nenhum preço unitário foi confirmado nesta verificação. As páginas de preço da AWS consultadas renderizam as tabelas de taxa com JavaScript, e a ferramenta de leitura não recebeu os valores. Por regra, nenhum preço foi inventado: todas as colunas de preço ficam como "não verificado" até alguém conferir na AWS Pricing Calculator (https://calculator.aws/#/addService) ou nas tabelas expandidas das páginas. Os totais também ficam em aberto, porque dependem desses valores.

Não use a taxa de "CPU Credits" dos T3 (modo ilimitado) como preço da instância: é cobrança extra, separada da taxa horária.

## Premissas de quantidade

- 730 horas por mês para EC2, para a instância RDS e para o IPv4 público (cobrado por hora por endereço público, em uso ou não).
- EC2 `t3.medium`, 1 instância, Linux.
- EBS gp3 de 30 GB, 1 volume.
- RDS PostgreSQL `db.t4g.micro`, Single-AZ, armazenamento gp3 de 20 GB.
- S3 Standard: 1 GB armazenado, com versionamento e expiração em 30 dias.
- Transferência de saída para a internet: premissa de 10 GB por mês. A página https://aws.amazon.com/ec2/pricing/on-demand/, lida em 2026-10-15, informa 100 GB de transferência de saída gratuita por mês. Esse número ainda precisa ser conferido na tabela oficial antes de virar custo zero.

## Tabela

| Item | Quantidade | Preço unitário | Total mensal | Fonte | Data da tentativa |
|---|---|---|---|---|---|
| EC2 `t3.medium` on-demand (Linux) | 730 h | não verificado | não verificado | https://aws.amazon.com/ec2/pricing/on-demand/ (tabela de preço não lida) | 2026-10-22 |
| IPv4 público da EC2 | 730 h | não verificado | não verificado | não verificado (página de IPv4 não consultada) | 2026-10-15 |
| EBS gp3 | 30 GB | não verificado (por GB-mês) | não verificado | https://aws.amazon.com/ebs/pricing/ (tabela de preço não lida) | 2026-10-15 |
| RDS PostgreSQL `db.t4g.micro` Single-AZ | 730 h | não verificado | não verificado | https://aws.amazon.com/rds/postgresql/pricing/ (tabela de preço não lida) | 2026-10-15 |
| RDS armazenamento gp3 | 20 GB | não verificado (por GB-mês) | não verificado | https://aws.amazon.com/rds/postgresql/pricing/ (tabela de preço não lida) | 2026-10-15 |
| S3 Standard | 1 GB | não verificado (por GB-mês) | não verificado | https://aws.amazon.com/s3/pricing/ (tabela de preço não lida) | 2026-10-15 |
| Transferência de saída para a internet | 10 GB, com 100 GB gratuitos por mês | não verificado (por GB acima da franquia) | não verificado | https://aws.amazon.com/ec2/pricing/on-demand/ (franquia de 100 GB lida; taxa não lida) | 2026-10-15 |
| **Total** | | | **não calculado** | | |

## Como fechar a tabela

1. Abrir a AWS Pricing Calculator e montar as seis linhas com as quantidades acima.
2. Preencher preço unitário e total de cada linha, com a data da consulta.
3. Conferir a franquia de 100 GB de transferência de saída na tabela oficial.
4. Somar as linhas. Qualquer linha que continuar "não verificado" mantém o total em aberto.

Itens fora desta estimativa: snapshots do RDS, Secrets Manager (segredo gerenciado da senha do RDS), registro DNS, certificados e impostos.
