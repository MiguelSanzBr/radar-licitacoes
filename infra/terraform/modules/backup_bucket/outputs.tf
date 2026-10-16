output "bucket_name" {
  description = "Nome do bucket de backups."
  value       = aws_s3_bucket.backups.bucket
}

output "bucket_arn" {
  description = "ARN do bucket, usado na policy da EC2."
  value       = aws_s3_bucket.backups.arn
}
