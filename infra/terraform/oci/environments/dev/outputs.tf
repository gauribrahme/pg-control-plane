output "cluster_id" {
  description = "Use with: oci ce cluster create-kubeconfig --cluster-id <id>"
  value       = module.oke.cluster_id
}

output "vcn_id" {
  value = module.network.vcn_id
}
