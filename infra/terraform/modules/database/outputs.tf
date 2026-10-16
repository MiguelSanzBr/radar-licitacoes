output "address" {
  description = "Host do PostgreSQL, sem a porta."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Porta do PostgreSQL."
  value       = aws_db_instance.this.port
}
