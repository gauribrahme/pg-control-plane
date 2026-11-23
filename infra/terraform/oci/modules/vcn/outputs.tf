output "vcn_id" {
  value = oci_core_vcn.this.id
}

output "k8s_api_subnet_id" {
  value = oci_core_subnet.k8s_api.id
}

output "nodes_subnet_id" {
  value = oci_core_subnet.nodes.id
}

output "lb_subnet_id" {
  value = oci_core_subnet.load_balancers.id
}
