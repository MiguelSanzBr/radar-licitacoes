provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

locals {
  name = "${var.project}-${var.environment}"
}

module "network" {
  source = "../../modules/network"

  name     = local.name
  vpc_cidr = var.vpc_cidr
  ssh_cidr = var.ssh_cidr
}

module "backup_bucket" {
  source = "../../modules/backup_bucket"

  name = var.backup_bucket_name
}

module "compute" {
  source = "../../modules/compute"

  name               = local.name
  subnet_id          = module.network.public_subnet_ids[0]
  security_group_ids = [module.network.app_security_group_id]
  instance_type      = var.instance_type
  backup_bucket_arn  = module.backup_bucket.bucket_arn
  app_repo_url       = var.app_repo_url
}

module "database" {
  source = "../../modules/database"

  name                      = local.name
  vpc_id                    = module.network.vpc_id
  subnet_ids                = module.network.public_subnet_ids
  allowed_security_group_id = module.network.app_security_group_id
  engine_version            = var.db_engine_version
}
