variable "region" {
  description = "Região AWS do ambiente demo."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Nome do projeto, usado em prefixos e tags."
  type        = string
  default     = "radar"
}

variable "environment" {
  description = "Nome do ambiente."
  type        = string
  default     = "demo"
}

variable "vpc_cidr" {
  description = "CIDR da VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "ssh_cidr" {
  description = "CIDR autorizado na porta 22 (ex.: o IP de casa em /32)."
  type        = string
}

variable "instance_type" {
  description = "Tipo da EC2."
  type        = string
  default     = "t4g.medium"
}

variable "db_engine_version" {
  description = "Versão major do PostgreSQL no RDS."
  type        = string
  default     = "17"
}

variable "backup_bucket_name" {
  description = "Nome global único do bucket de backups."
  type        = string
}

variable "app_repo_url" {
  description = "URL do repositório clonado na EC2 para subir o compose."
  type        = string
}
