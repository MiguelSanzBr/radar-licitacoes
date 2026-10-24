variable "name" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "subnet_id" {
  description = "Sub-rede pública onde a EC2 é criada."
  type        = string
}

variable "security_group_ids" {
  description = "Security groups da EC2."
  type        = list(string)
}

variable "instance_type" {
  description = "Tipo da EC2 (x86_64, porque a AMI é AL2023 x86_64 e o main.yml publica uma imagem só amd64)."
  type        = string
  default     = "t3.medium"
}

variable "root_volume_size" {
  description = "Tamanho do volume raiz gp3, em GB."
  type        = number
  default     = 30
}

variable "backup_bucket_arn" {
  description = "ARN do bucket de backups, liberado para gravação e leitura pela instância."
  type        = string
}

variable "app_repo_url" {
  description = "URL do repositório clonado no user_data para subir o compose."
  type        = string
}

variable "compose_version" {
  description = "Versão do plugin Docker Compose instalado no user_data."
  type        = string
  default     = "v5.6.1"
}

variable "image_registry" {
  description = "Repositório da imagem no GHCR, sem a tag (ex.: ghcr.io/dono/radar-licitacoes). O pacote precisa ser público, porque o user_data não faz login."
  type        = string
}

variable "image_tag" {
  description = "Tag da imagem publicada pelo main.yml: o sha do commit ou a versão semver. Não existe a tag latest."
  type        = string
}
