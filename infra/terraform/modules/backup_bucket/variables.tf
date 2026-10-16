variable "name" {
  description = "Nome global único do bucket de backups."
  type        = string
}

variable "retention_days" {
  description = "Dias até a expiração dos objetos e das versões antigas."
  type        = number
  default     = 30
}
