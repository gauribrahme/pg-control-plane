variable "compartment_id" {
  type = string
}

variable "name" {
  type = string
}

variable "vcn_id" {
  type = string
}

variable "k8s_api_subnet_id" {
  type = string
}

variable "nodes_subnet_id" {
  type = string
}

variable "lb_subnet_id" {
  type = string
}

variable "kubernetes_version" {
  description = "For example v1.30.1; must be a version OKE currently supports"
  type        = string
}

variable "availability_domain" {
  description = "Availability domain name for the node pool"
  type        = string
}

variable "node_image_id" {
  description = "OCID of an OKE-compatible node image matching the node shape architecture"
  type        = string
}

variable "node_shape" {
  type    = string
  default = "VM.Standard.A1.Flex"
}

variable "node_ocpus" {
  type    = number
  default = 2
}

variable "node_memory_gbs" {
  type    = number
  default = 12
}

variable "node_count" {
  type    = number
  default = 2
}
