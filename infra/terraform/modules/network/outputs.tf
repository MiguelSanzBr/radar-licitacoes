output "vpc_id" {
  description = "ID da VPC."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "IDs das duas sub-redes públicas, em AZs distintas."
  value       = aws_subnet.public[*].id
}

output "app_security_group_id" {
  description = "Security group da EC2 e origem permitida para o banco."
  value       = aws_security_group.app.id
}
