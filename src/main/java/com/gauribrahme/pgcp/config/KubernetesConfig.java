package com.gauribrahme.pgcp.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Active with the k8s profile only. Uses in-cluster config when running in a pod, else the local kubeconfig. */
@Configuration
@Profile("k8s")
public class KubernetesConfig {
    @Bean(destroyMethod = "close")
    KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
