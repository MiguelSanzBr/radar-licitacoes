output "app_public_ip" {
  description = "IP público da EC2 da aplicação."
  value       = module.compute.public_ip
}

output "db_address" {
  description = "Host do PostgreSQL (privado, acessível só pela EC2)."
  value       = module.database.address
}

output "backup_bucket_name" {
  description = "Bucket S3 de backups."
  value       = module.backup_bucket.bucket_name
}
