package com.gauribrahme.pgcp.provisioner;

import com.gauribrahme.pgcp.instance.Plan;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs each instance as a single-replica StatefulSet of postgres in a namespace-per-tenant.
 *
 * <p>Idempotency strategy: every create step is "get; if missing, create". Names are derived from the
 * instance id, so a re-run after a crash converges on the same objects. The password Secret is never
 * regenerated if it already exists (otherwise a retry would lock the app out of its own database).
 */
@Component
@Profile("k8s")
public class KubernetesProvisioner implements DatabaseProvisioner {
    private static final Logger log = LoggerFactory.getLogger(KubernetesProvisioner.class);

    static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    static final String L_INSTANCE = "pgcp/instance-id";
    static final String L_TENANT = "pgcp/tenant";
    static final String L_PLAN = "pgcp/plan";
    static final String A_STORAGE = "pgcp/storage-gb";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KubernetesClient client;

    public KubernetesProvisioner(KubernetesClient client) {
        this.client = client;
    }

    static String namespace(String tenantId) { return "tenant-" + tenantId; }
    static String resourceName(String instanceId) { return "pg-" + instanceId; }

    @Override
    public ProvisionResult create(ProvisionSpec spec) {
        String ns = namespace(spec.tenantId());
        String name = resourceName(spec.instanceId());
        Map<String, String> labels = labels(spec);

        if (client.namespaces().withName(ns).get() == null) {
            Namespace namespace = new NamespaceBuilder().withNewMetadata().withName(ns)
                    .addToLabels(MANAGED_BY, "pg-control-plane").addToLabels(L_TENANT, spec.tenantId())
                    .endMetadata().build();
            client.namespaces().resource(namespace).create();
        }
        if (client.secrets().inNamespace(ns).withName(name).get() == null) {
            Secret secret = new SecretBuilder().withNewMetadata().withName(name).withNamespace(ns)
                    .withLabels(labels).endMetadata()
                    .withType("Opaque").addToStringData("password", randomPassword()).build();
            client.secrets().resource(secret).create();
        }
        if (client.services().inNamespace(ns).withName(name).get() == null) {
            Service service = new ServiceBuilder().withNewMetadata().withName(name).withNamespace(ns)
                    .withLabels(labels).endMetadata()
                    .withNewSpec().addToSelector(L_INSTANCE, spec.instanceId())
                    .addNewPort().withName("postgres").withPort(5432).endPort().endSpec().build();
            client.services().resource(service).create();
        }
        if (client.apps().statefulSets().inNamespace(ns).withName(name).get() == null) {
            client.apps().statefulSets().resource(statefulSet(spec, ns, name, labels)).create();
        }
        log.info("kubernetes create converged namespace={} statefulset={}", ns, name);
        return new ProvisionResult(name + "." + ns + ".svc.cluster.local:5432");
    }

    private StatefulSet statefulSet(ProvisionSpec spec, String ns, String name, Map<String, String> labels) {
        return new StatefulSetBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).withLabels(labels)
                .addToAnnotations(A_STORAGE, String.valueOf(spec.storageGb())).endMetadata()
                .withNewSpec()
                .withServiceName(name).withReplicas(1)
                .withNewSelector().addToMatchLabels(L_INSTANCE, spec.instanceId()).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(labels).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("postgres").withImage("postgres:" + spec.pgVersion())
                .addNewPort().withName("postgres").withContainerPort(5432).endPort()
                .addNewEnv().withName("POSTGRES_PASSWORD").withNewValueFrom().withNewSecretKeyRef()
                .withName(name).withKey("password").endSecretKeyRef().endValueFrom().endEnv()
                .addNewEnv().withName("PGDATA").withValue("/var/lib/postgresql/data/pgdata").endEnv()
                .withResources(resources(spec.plan()))
                .addNewVolumeMount().withName("data").withMountPath("/var/lib/postgresql/data").endVolumeMount()
                .withNewReadinessProbe().withNewExec().addToCommand("pg_isready", "-U", "postgres").endExec()
                .withInitialDelaySeconds(5).withPeriodSeconds(10).endReadinessProbe()
                .endContainer()
                .endSpec().endTemplate()
                .addNewVolumeClaimTemplate()
                .withNewMetadata().withName("data").withLabels(labels).endMetadata()
                .withNewSpec().addToAccessModes("ReadWriteOnce")
                .withNewResources().addToRequests("storage", new Quantity(spec.storageGb() + "Gi")).endResources()
                .endSpec().endVolumeClaimTemplate()
                .endSpec().build();
    }

    @Override
    public Optional<ActualState> describe(String tenantId, String instanceId) {
        StatefulSet sts = client.apps().statefulSets().inNamespace(namespace(tenantId))
                .withName(resourceName(instanceId)).get();
        if (sts == null) {
            return Optional.empty();
        }
        Plan plan = Plan.fromJson(sts.getMetadata().getLabels().get(L_PLAN));
        int storage = Integer.parseInt(sts.getMetadata().getAnnotations().get(A_STORAGE));
        Integer readyReplicas = sts.getStatus() == null ? null : sts.getStatus().getReadyReplicas();
        boolean ready = readyReplicas != null && readyReplicas >= 1;
        String endpoint = resourceName(instanceId) + "." + namespace(tenantId) + ".svc.cluster.local:5432";
        return Optional.of(new ActualState(plan, storage, ready, endpoint));
    }

    @Override
    public void resize(ProvisionSpec spec) {
        String ns = namespace(spec.tenantId());
        String name = resourceName(spec.instanceId());
        StatefulSet sts = client.apps().statefulSets().inNamespace(ns).withName(name).get();
        if (sts == null) {
            throw new ProvisioningException("cannot resize: statefulset missing for " + spec.instanceId());
        }
        int currentStorage = Integer.parseInt(sts.getMetadata().getAnnotations().get(A_STORAGE));
        int targetStorage = Math.max(currentStorage, spec.storageGb()); // storage only grows
        // CPU/memory: replacing the pod template triggers a rolling restart of the single replica.
        sts.getSpec().getTemplate().getSpec().getContainers().get(0).setResources(resources(spec.plan()));
        sts.getMetadata().getLabels().put(L_PLAN, spec.plan().json());
        sts.getMetadata().getAnnotations().put(A_STORAGE, String.valueOf(targetStorage));
        client.apps().statefulSets().resource(sts).update();
        // StatefulSet volumeClaimTemplates are immutable, so expand the existing PVC directly
        // (requires a StorageClass with allowVolumeExpansion=true).
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(ns)
                .withName("data-" + name + "-0").get();
        if (pvc != null && targetStorage > currentStorage) {
            PersistentVolumeClaim grown = new PersistentVolumeClaimBuilder(pvc).editSpec().editResources()
                    .addToRequests("storage", new Quantity(targetStorage + "Gi")).endResources().endSpec().build();
            client.persistentVolumeClaims().resource(grown).update();
        }
    }

    @Override
    public String backup(ProvisionSpec spec, String backupId) {
        String ns = namespace(spec.tenantId());
        String name = resourceName(spec.instanceId());
        String backupName = "backup-" + backupId;
        if (client.persistentVolumeClaims().inNamespace(ns).withName(backupName).get() == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder().withNewMetadata().withName(backupName)
                    .withNamespace(ns).withLabels(labels(spec)).endMetadata()
                    .withNewSpec().addToAccessModes("ReadWriteOnce")
                    .withNewResources().addToRequests("storage", new Quantity(spec.storageGb() + "Gi")).endResources()
                    .endSpec().build();
            client.persistentVolumeClaims().resource(pvc).create();
        }
        if (client.batch().v1().jobs().inNamespace(ns).withName(backupName).get() == null) {
            Job job = new JobBuilder().withNewMetadata().withName(backupName).withNamespace(ns)
                    .withLabels(labels(spec)).endMetadata()
                    .withNewSpec().withBackoffLimit(3)
                    .withNewTemplate().withNewSpec().withRestartPolicy("Never")
                    .addNewContainer().withName("pg-dump").withImage("postgres:" + spec.pgVersion())
                    .addToCommand("sh", "-c", "pg_dump -h " + name + " -U postgres -Fc -f /backup/" + backupId + ".dump")
                    .addNewEnv().withName("PGPASSWORD").withNewValueFrom().withNewSecretKeyRef()
                    .withName(name).withKey("password").endSecretKeyRef().endValueFrom().endEnv()
                    .addNewVolumeMount().withName("backup").withMountPath("/backup").endVolumeMount()
                    .endContainer()
                    .addNewVolume().withName("backup").withNewPersistentVolumeClaim().withClaimName(backupName)
                    .endPersistentVolumeClaim().endVolume()
                    .endSpec().endTemplate().endSpec().build();
            client.batch().v1().jobs().resource(job).create();
        }
        return "pvc://" + ns + "/" + backupName; // Job completion is not awaited; see NOTES.md
    }

    @Override
    public void delete(String tenantId, String instanceId) {
        String ns = namespace(tenantId);
        String name = resourceName(instanceId);
        // Deleting a missing object is a no-op in fabric8, which is exactly the idempotency we need.
        client.apps().statefulSets().inNamespace(ns).withName(name).delete();
        client.services().inNamespace(ns).withName(name).delete();
        client.secrets().inNamespace(ns).withName(name).delete();
        client.batch().v1().jobs().inNamespace(ns).withLabel(L_INSTANCE, instanceId).delete();
        client.persistentVolumeClaims().inNamespace(ns).withLabel(L_INSTANCE, instanceId).delete();
    }

    private static Map<String, String> labels(ProvisionSpec spec) {
        return Map.of(MANAGED_BY, "pg-control-plane", L_INSTANCE, spec.instanceId(), L_TENANT, spec.tenantId(),
                L_PLAN, spec.plan().json());
    }

    private static ResourceRequirements resources(Plan plan) {
        Map<String, Quantity> sizes = Map.of("cpu", new Quantity(String.valueOf(plan.cpu())),
                "memory", new Quantity(plan.memoryGb() + "Gi"));
        return new ResourceRequirementsBuilder().withRequests(sizes).withLimits(sizes).build();
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
