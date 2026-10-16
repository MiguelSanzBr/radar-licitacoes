variable "name" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "vpc_id" {
  description = "VPC do banco."
  type        = string
}

variable "subnet_ids" {
  description = "Sub-redes do DB subnet group (precisa de pelo menos duas AZs)."
  type        = list(string)
}

variable "allowed_security_group_id" {
  description = "Único security group com acesso à porta 5432 (o da EC2)."
  type        = string
}

variable "engine_version" {
  description = "Versão major do PostgreSQL. Trocar para 18 quando a RDS oferecer no db.t4g.micro."
  type        = string
  default     = "17"
}

variable "instance_class" {
  description = "Classe da instância RDS."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Armazenamento gp3 inicial, em GB."
  type        = number
  default     = 20
}

variable "db_name" {
  description = "Nome do banco criado na instância."
  type        = string
  default     = "radar"
}

variable "username" {
  description = "Usuário administrador. A senha fica no Secrets Manager (gerenciada pela RDS)."
  type        = string
  default     = "radar"
}
