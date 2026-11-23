variable "region" {
  description = "OCI region identifier, for example ap-mumbai-1"
  type        = string
  default     = "ap-mumbai-1"
}

variable "tenancy_ocid" {
  description = "Tenancy OCID, used to look up availability domains"
  type        = string
}

variable "compartment_id" {
  description = "Compartment OCID for all resources"
  type        = string
}

variable "kubernetes_version" {
  description = "OKE Kubernetes version"
  type        = string
  default     = "v1.30.1"
}

variable "node_image_id" {
  description = "OKE node image OCID (aarch64 for A1.Flex)"
  type        = string
}
