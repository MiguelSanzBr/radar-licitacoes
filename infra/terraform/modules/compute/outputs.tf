output "instance_id" {
  description = "ID da EC2."
  value       = aws_instance.app.id
}

output "public_ip" {
  description = "IP público da EC2."
  value       = aws_instance.app.public_ip
}
