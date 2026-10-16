variable "name" {
  description = "Prefixo dos nomes dos recursos."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR da VPC. As duas sub-redes públicas são fatias /24 dele."
  type        = string
  default     = "10.0.0.0/16"
}

variable "ssh_cidr" {
  description = "CIDR autorizado na porta 22. Prefixo /16 ou mais restrito; 0.0.0.0/0 não é aceito."
  type        = string

  validation {
    condition     = can(cidrhost(var.ssh_cidr, 0)) && can(regex("/(1[6-9]|2[0-9]|3[0-2])$", var.ssh_cidr))
    error_message = "ssh_cidr precisa ser um CIDR válido com prefixo /16 ou mais restrito (ex.: /32). 0.0.0.0/0 não é aceito."
  }
}
