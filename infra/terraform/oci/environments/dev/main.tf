terraform {
  required_version = ">= 1.5.0"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }

  # Remote state in OCI Object Storage via its S3-compatible API. Example only: see backend.hcl.example
  # and the README in the parent folder. Initialise with: terraform init -backend-config=backend.hcl
  backend "s3" {
    bucket = "pgcp-terraform-state"
    key    = "dev/terraform.tfstate"
    region = "ap-mumbai-1"

    skip_region_validation      = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
    use_path_style              = true
    # endpoints = { s3 = "https://<namespace>.compat.objectstorage.<region>.oraclecloud.com" } via backend.hcl
  }
}

provider "oci" {
  region = var.region
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

module "network" {
  source         = "../../modules/vcn"
  compartment_id = var.compartment_id
  name           = "pgcp-dev"
}

module "oke" {
  source              = "../../modules/oke"
  compartment_id      = var.compartment_id
  name                = "pgcp-dev"
  vcn_id              = module.network.vcn_id
  k8s_api_subnet_id   = module.network.k8s_api_subnet_id
  nodes_subnet_id     = module.network.nodes_subnet_id
  lb_subnet_id        = module.network.lb_subnet_id
  kubernetes_version  = var.kubernetes_version
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  node_image_id       = var.node_image_id
}
