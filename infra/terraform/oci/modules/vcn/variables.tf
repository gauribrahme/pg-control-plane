variable "compartment_id" {
  description = "OCID of the compartment that owns the network"
  type        = string
}

variable "name" {
  description = "Name prefix for all resources"
  type        = string
}

variable "vcn_cidr" {
  description = "CIDR block of the VCN; /16 recommended so three /24 subnets fit"
  type        = string
  default     = "10.0.0.0/16"
}

variable "dns_label" {
  description = "VCN DNS label (alphanumeric, max 15 chars)"
  type        = string
  default     = "pgcp"
}

variable "api_allowed_cidr" {
  description = "CIDR allowed to reach the Kubernetes API endpoint; restrict to your office or VPN range"
  type        = string
  default     = "0.0.0.0/0"
}
