terraform {
  required_version = ">= 1.5.0"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }
}

# BASIC_CLUSTER has no control-plane fee; ENHANCED_CLUSTER adds virtual nodes and add-on management.
resource "oci_containerengine_cluster" "this" {
  compartment_id     = var.compartment_id
  name               = "${var.name}-oke"
  kubernetes_version = var.kubernetes_version
  vcn_id             = var.vcn_id
  type               = "BASIC_CLUSTER"

  endpoint_config {
    is_public_ip_enabled = true
    subnet_id            = var.k8s_api_subnet_id
  }

  options {
    service_lb_subnet_ids = [var.lb_subnet_id]
  }
}

resource "oci_containerengine_node_pool" "this" {
  compartment_id     = var.compartment_id
  cluster_id         = oci_containerengine_cluster.this.id
  name               = "${var.name}-pool"
  kubernetes_version = var.kubernetes_version
  node_shape         = var.node_shape

  # Flex shapes need explicit sizing. VM.Standard.A1.Flex (Arm) is Always Free eligible up to
  # 4 OCPU and 24 GB in total across the tenancy; check current limits before applying.
  node_shape_config {
    ocpus         = var.node_ocpus
    memory_in_gbs = var.node_memory_gbs
  }

  node_config_details {
    size = var.node_count

    placement_configs {
      availability_domain = var.availability_domain
      subnet_id           = var.nodes_subnet_id
    }
  }

  node_source_details {
    source_type = "IMAGE"
    image_id    = var.node_image_id
  }
}
